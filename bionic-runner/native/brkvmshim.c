/*
 * brkvmshim: QEMU に LD_PRELOAD で読み込ませる、KVM の呼び出しの小さな調整役 (arm64 の端末用)。
 *
 * Pixel などの Android は KVM を「保護モード (pKVM)」で動かしていて、仮想マシンのメモリの登録のうち
 * 「読み取り専用 (KVM_MEM_READONLY)」の領域を拒否する (EPERM)。ところがカーネルは「使える」と答えるので、
 * QEMU は ROM や フラッシュ (UEFI などを置く場所) をその形で登録しようとして、起動直後に止まる。
 *
 * そこで、QEMU が「読み取り専用の領域を使えるか」(KVM_CAP_READONLY_MEM) を聞いたときだけ「使えない」と
 * 答える。すると QEMU は、その領域を登録せず、読み書きのたびに自分で処理する形 (装置と同じ扱い) にする。
 * ほかの呼び出しは何も変えずにそのまま通す。
 *
 * 環境変数 BRKVM_SHIM_LOG=1 のときは、メモリの登録 (場所・大きさ・種類・結果) を標準エラーに出す
 * (診断用。中身のデータは出さない)。
 */
#define _GNU_SOURCE
#include <errno.h>
#include <linux/kvm.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <unistd.h>

static int log_on = -1;

static int logging(void)
{
    if (log_on < 0) {
        const char *v = getenv("BRKVM_SHIM_LOG");
        log_on = v != NULL && v[0] == '1';
    }
    return log_on;
}

/* bionic の ioctl と同じ形 (int, int, ...) */
int ioctl(int fd, int request, ...)
{
    va_list ap;
    unsigned long arg;
    long r;
    va_start(ap, request);
    arg = va_arg(ap, unsigned long);
    va_end(ap);

    if ((unsigned)request == (unsigned)KVM_CHECK_EXTENSION && arg == KVM_CAP_READONLY_MEM) {
        if (logging()) fprintf(stderr, "brkvmshim: 読み取り専用のメモリ領域は「使えない」と答えました\n");
        return 0;
    }
    r = syscall(SYS_ioctl, fd, (unsigned long)(unsigned)request, arg);
    if (logging() && ((unsigned)request == (unsigned)KVM_SET_USER_MEMORY_REGION
                      || (unsigned)request == (unsigned)KVM_SET_USER_MEMORY_REGION2)) {
        const struct kvm_userspace_memory_region *m = (const struct kvm_userspace_memory_region *)arg;
        int e = errno;
        fprintf(stderr, "brkvmshim: メモリの登録 slot=%u 場所=0x%llx 大きさ=0x%llx 種類=0x%x → %s\n",
                m->slot, (unsigned long long)m->guest_phys_addr, (unsigned long long)m->memory_size,
                m->flags, r == 0 ? "ok" : strerror(e));
        errno = e;
    }
    return (int)r;
}
