/*
 * brshim: root なしで glibc 用プログラムを動かすための補助ライブラリ。
 *
 * Android のアプリのプロセスには、使ってよいシステムコールを絞る仕組み (seccomp) が掛かっている。
 * 許可されていないシステムコールを呼ぶと、そのプロセスは SIGSYS で即座に終了させられる。
 * glibc は新しいシステムコール (clone3 など) をまず試し、「未対応 (ENOSYS)」と返されたら古いものに
 * 切り替える作りなので、終了させられる代わりに ENOSYS が返るようにすれば、そのまま動き続ける。
 *
 * このライブラリは読み込まれた時点で SIGSYS の処理を登録し、止められたシステムコールの戻り値を
 * ENOSYS に書き換えて処理を続けさせる。glibc のローダの --preload で、プログラムより先に読み込ませる。
 *
 * プログラムが自分で SIGSYS の処理を登録しようとしたとき (Wine など) は、その登録を横取りして覚えておき、
 * こちらの処理は外さない。そのうえで、seccomp で止められたもの以外の SIGSYS だけをプログラムの処理へ渡す。
 * (外されると、止められたシステムコールが「未対応」にならず、プログラムが想定していない SIGSYS を
 *  受け取って動作が壊れる)
 *
 * 環境変数 BR_DROP_EXEC=1 のとき (Box64 で x86_64 のプログラムを動かすとき) は、mmap と mprotect で
 * 「実行できる」印 (PROT_EXEC) が Android に拒否されたら、その印を外してやり直す。
 * Android (SELinux) は、アプリのデータフォルダのファイルを実行できる形でメモリに読み込むことを禁じている。
 * Wine は Windows の DLL をそうやって読み込もうとするが、Box64 の上では x86 の命令を CPU が直接
 * 実行することはない (Box64 が ARM の命令に変換して動かす) ので、この印は無くてよい。
 * Termux 版の glibc の mprotect は、拒否されたときに独自の回避処理を走らせ、それが落ちることがあるので、
 * glibc の関数ではなくシステムコールを直接呼ぶ。64bit の CPU だけ (Box64 は 64bit の ARM だけ)。
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <ucontext.h>

#ifndef SYS_SECCOMP
#define SYS_SECCOMP 1 /* si_code: seccomp によって止められた */
#endif

typedef int (*sigaction_fn)(int, const struct sigaction *, struct sigaction *);

/* プログラムが登録しようとした SIGSYS の処理 (まだなら既定の動作) */
static struct sigaction app_action;
static int app_set;

static sigaction_fn real_sigaction(void)
{
    static sigaction_fn fn;
    if (!fn) fn = (sigaction_fn)dlsym(RTLD_NEXT, "sigaction");
    return fn;
}

static void on_sigsys(int sig, siginfo_t *info, void *context)
{
    ucontext_t *uc = (ucontext_t *)context;
    if (info->si_code == SYS_SECCOMP) {
        /* 止められたシステムコールの戻り値が入るレジスタに「未対応」を入れて、続きから再開させる */
#if defined(__aarch64__)
        uc->uc_mcontext.regs[0] = (unsigned long)-ENOSYS;
#elif defined(__arm__)
        uc->uc_mcontext.arm_r0 = (unsigned long)-ENOSYS;
#elif defined(__x86_64__)
        uc->uc_mcontext.gregs[REG_RAX] = -ENOSYS;
#elif defined(__i386__)
        uc->uc_mcontext.gregs[REG_EAX] = -ENOSYS;
#else
#error "unsupported CPU"
#endif
        return;
    }
    /* seccomp 以外の理由で届いた SIGSYS は、プログラムが登録した処理へ (無ければ本来どおり終了させる) */
    if (app_set && (app_action.sa_flags & SA_SIGINFO) && app_action.sa_sigaction) {
        app_action.sa_sigaction(sig, info, context);
        return;
    }
    if (app_set && app_action.sa_handler != SIG_DFL && app_action.sa_handler != SIG_IGN
            && app_action.sa_handler != NULL) {
        app_action.sa_handler(sig);
        return;
    }
    if (app_set && app_action.sa_handler == SIG_IGN) return;
    struct sigaction dfl;
    memset(&dfl, 0, sizeof(dfl));
    dfl.sa_handler = SIG_DFL;
    sigaction_fn fn = real_sigaction();
    if (fn) fn(sig, &dfl, NULL);
    raise(sig);
}

static void install(void)
{
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = on_sigsys;
    /* SA_NODEFER: 処理中にもう一度止められても受け取れるようにする */
    sa.sa_flags = SA_SIGINFO | SA_NODEFER;
    sigemptyset(&sa.sa_mask);
    sigaction_fn fn = real_sigaction();
    if (fn) fn(SIGSYS, &sa, NULL);
}

/* sigaction の横取り: SIGSYS だけは登録を覚えるだけにして、こちらの処理を残す */
int sigaction(int sig, const struct sigaction *act, struct sigaction *old)
{
    sigaction_fn fn = real_sigaction();
    if (sig != SIGSYS) {
        if (!fn) {
            errno = ENOSYS;
            return -1;
        }
        return fn(sig, act, old);
    }
    if (old) {
        if (app_set) {
            *old = app_action;
        } else {
            memset(old, 0, sizeof(*old));
            old->sa_handler = SIG_DFL;
        }
    }
    if (act) {
        app_action = *act;
        app_set = 1;
    }
    return 0;
}

/* signal 系の関数は glibc の中で sigaction を直接呼ぶので、SIGSYS のときだけ同じように横取りする */
typedef void (*handler_fn)(int);

static handler_fn set_handler(int sig, handler_fn h, const char *name)
{
    if (sig != SIGSYS) {
        handler_fn (*next)(int, handler_fn) = (handler_fn (*)(int, handler_fn))dlsym(RTLD_NEXT, name);
        return next ? next(sig, h) : SIG_ERR;
    }
    struct sigaction act, old;
    memset(&act, 0, sizeof(act));
    act.sa_handler = h;
    sigaction(SIGSYS, &act, &old);
    return (old.sa_flags & SA_SIGINFO) ? (handler_fn)(void *)old.sa_sigaction : old.sa_handler;
}

handler_fn signal(int sig, handler_fn h)
{
    return set_handler(sig, h, "signal");
}

handler_fn bsd_signal(int sig, handler_fn h)
{
    return set_handler(sig, h, "bsd_signal");
}

handler_fn sysv_signal(int sig, handler_fn h)
{
    return set_handler(sig, h, "sysv_signal");
}

#if defined(__LP64__)
/* BR_DROP_EXEC=1 のときだけ 1 (読み込まれた時点で決める) */
static int drop_exec;

static int denied(long r)
{
    return r == -1 && (errno == EACCES || errno == EPERM);
}

void *mmap(void *addr, size_t len, int prot, int flags, int fd, off_t off)
{
    long r = syscall(SYS_mmap, addr, len, prot, flags, fd, off);
    if (drop_exec && (prot & PROT_EXEC) && denied(r)) {
        r = syscall(SYS_mmap, addr, len, prot & ~PROT_EXEC, flags, fd, off);
    }
    return (void *)r;
}

void *mmap64(void *addr, size_t len, int prot, int flags, int fd, off_t off)
{
    return mmap(addr, len, prot, flags, fd, off);
}

int mprotect(void *addr, size_t len, int prot)
{
    long r = syscall(SYS_mprotect, addr, len, prot);
    if (drop_exec && (prot & PROT_EXEC) && denied(r)) {
        r = syscall(SYS_mprotect, addr, len, prot & ~PROT_EXEC);
    }
    return (int)r;
}
#endif

__attribute__((constructor))
static void brshim_init(void)
{
#if defined(__LP64__)
    const char *e = getenv("BR_DROP_EXEC");
    drop_exec = e && e[0] == '1';
#endif
    install();
}
