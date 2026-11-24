/*
 * brkvmshim: QEMU に LD_PRELOAD で読み込ませる、KVM の呼び出しの小さな調整役 (arm64 の端末用)。
 *
 * Pixel などの Android は KVM を「保護モード (pKVM)」で動かしていて、仮想マシンのメモリの登録のうち
 *   - 読み取り専用 (KVM_MEM_READONLY) の領域
 *   - 書き込みを記録する (KVM_MEM_LOG_DIRTY_PAGES) 領域
 *   - 仮想 CPU を動かしたあとの、登録の削除
 * を拒否する (EPERM)。ところがカーネルは読み取り専用の領域を「使える」と答えるので、QEMU は ROM や
 * フラッシュ (UEFI を置く場所) をその形で登録しようとして、起動直後に止まる。
 *
 * ふつうの形 (Linux を直接起動する仮想マシン):
 *   QEMU が「読み取り専用の領域を使えるか」(KVM_CAP_READONLY_MEM) を聞いたときだけ「使えない」と答える。
 *   すると QEMU は、その領域を登録せず、読み書きのたびに自分で処理する形 (装置と同じ扱い) にする。
 *
 * フラッシュの形 (BRKVM_SHIM_FLASH=1。UEFI で起動する Windows 用):
 *   UEFI はフラッシュの上の命令をそのまま実行するので、装置と同じ扱いにすると動かない
 *   (KVM は装置の場所の命令を実行できない)。そこで「使える」と答えたうえで:
 *   - 0x04000000 より下 (1 つ目のフラッシュ = UEFI の本体) の読み取り専用の登録は、ふつうのメモリとして
 *     登録する (中で書き換えても、QEMU のメモリの上の写しが変わるだけ。元のファイルは変わらない)
 *   - それ以外の読み取り専用の登録 (2 つ目のフラッシュ = UEFI の設定の保存場所など) は、登録したことに
 *     して実際には登録しない。読み書きは QEMU が処理する (設定の書き込みも正しくファイルに残る)。
 *     あとでその登録を消すとき (フラッシュの書き込みの手順の途中で起きる) も、消したことにする
 *   - 書き込みを記録する印は外して登録する (記録は使えない)
 *
 * ほかの呼び出しは何も変えずにそのまま通す。
 * 環境変数 BRKVM_SHIM_LOG=1 のときは、メモリの登録 (場所・大きさ・種類・結果) を標準エラーに出す
 * (診断用。中身のデータは出さない)。
 */
#define _GNU_SOURCE
#include <errno.h>
#include <linux/kvm.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <unistd.h>

#define FLASH0_END 0x04000000ULL
#define MAX_SLOTS 4096

static int log_on = -1, flash_on = -1;
/* 登録したことにしただけの登録 (slot の番号ごと) */
static unsigned char fake[MAX_SLOTS];
static pthread_mutex_t mu = PTHREAD_MUTEX_INITIALIZER;

static int env_on(const char *name, int *cache)
{
    if (*cache < 0) {
        const char *v = getenv(name);
        *cache = v != NULL && v[0] == '1';
    }
    return *cache;
}

static void note(const struct kvm_userspace_memory_region *m, const char *what)
{
    if (env_on("BRKVM_SHIM_LOG", &log_on)) {
        fprintf(stderr, "brkvmshim: メモリの登録 slot=%u 場所=0x%llx 大きさ=0x%llx 種類=0x%x → %s\n",
                m->slot, (unsigned long long)m->guest_phys_addr, (unsigned long long)m->memory_size,
                m->flags, what);
    }
}

/* フラッシュの形でのメモリの登録。kernel に渡すなら 1、ここで済ませたなら 0 (*ret に結果) */
static int flash_region(struct kvm_userspace_memory_region *m, long *ret)
{
    unsigned slot = m->slot & 0xffff; /* 上位はアドレス空間の番号 */
    int was_fake;
    if (slot >= MAX_SLOTS) return 1;
    pthread_mutex_lock(&mu);
    was_fake = fake[slot];
    if (m->memory_size == 0) {
        /* 削除: 登録したことにしただけなら、消したことにする */
        fake[slot] = 0;
        pthread_mutex_unlock(&mu);
        if (was_fake) {
            note(m, "消したことにした");
            *ret = 0;
            return 0;
        }
        return 1;
    }
    if (m->flags & KVM_MEM_READONLY) {
        if (m->guest_phys_addr + m->memory_size <= FLASH0_END && !was_fake) {
            pthread_mutex_unlock(&mu);
            m->flags &= ~KVM_MEM_READONLY;
            return 1; /* UEFI の本体: ふつうのメモリとして登録 */
        }
        fake[slot] = 1;
        pthread_mutex_unlock(&mu);
        note(m, "登録したことにした (読み書きは QEMU が処理)");
        *ret = 0;
        return 0;
    }
    if (was_fake) {
        /* 登録したことにしただけのものの、印の変更など */
        pthread_mutex_unlock(&mu);
        note(m, "登録したことにしたものの変更");
        *ret = 0;
        return 0;
    }
    pthread_mutex_unlock(&mu);
    return 1;
}

/* bionic の ioctl と同じ形 (int, int, ...) */
int ioctl(int fd, int request, ...)
{
    va_list ap;
    unsigned long arg;
    long r;
    unsigned req = (unsigned)request;
    va_start(ap, request);
    arg = va_arg(ap, unsigned long);
    va_end(ap);

    if (req == (unsigned)KVM_CHECK_EXTENSION && arg == KVM_CAP_READONLY_MEM
        && !env_on("BRKVM_SHIM_FLASH", &flash_on)) {
        if (env_on("BRKVM_SHIM_LOG", &log_on)) {
            fprintf(stderr, "brkvmshim: 読み取り専用のメモリ領域は「使えない」と答えました\n");
        }
        return 0;
    }
    if (req == (unsigned)KVM_SET_USER_MEMORY_REGION || req == (unsigned)KVM_SET_USER_MEMORY_REGION2) {
        /* 2 つの形は先頭が同じ。書き換えるので写しを使う (呼び出し側のものは変えない) */
        struct kvm_userspace_memory_region2 copy;
        size_t n = req == (unsigned)KVM_SET_USER_MEMORY_REGION2 ? sizeof(struct kvm_userspace_memory_region2)
                                                               : sizeof(struct kvm_userspace_memory_region);
        struct kvm_userspace_memory_region *m = (struct kvm_userspace_memory_region *)&copy;
        memset(&copy, 0, sizeof(copy));
        memcpy(&copy, (const void *)arg, n);
        if (m->flags & KVM_MEM_LOG_DIRTY_PAGES) {
            m->flags &= ~KVM_MEM_LOG_DIRTY_PAGES; /* 保護モードでは使えない */
        }
        if (env_on("BRKVM_SHIM_FLASH", &flash_on)) {
            long ret;
            if (!flash_region(m, &ret)) return (int)ret;
        }
        r = syscall(SYS_ioctl, fd, (unsigned long)req, (unsigned long)&copy);
        {
            int e = errno;
            note(m, r == 0 ? "ok" : strerror(e));
            errno = e;
        }
        return (int)r;
    }
    r = syscall(SYS_ioctl, fd, (unsigned long)req, arg);
    return (int)r;
}
