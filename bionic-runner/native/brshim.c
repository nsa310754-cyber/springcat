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
 */
#define _GNU_SOURCE
#include <errno.h>
#include <signal.h>
#include <string.h>
#include <ucontext.h>

#ifndef SYS_SECCOMP
#define SYS_SECCOMP 1 /* si_code: seccomp によって止められた */
#endif

static void on_sigsys(int sig, siginfo_t *info, void *context) {
    ucontext_t *uc = (ucontext_t *)context;
    if (info->si_code != SYS_SECCOMP) {
        /* seccomp 以外の理由で届いた SIGSYS は、本来どおりプロセスを終了させる */
        signal(sig, SIG_DFL);
        raise(sig);
        return;
    }
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
}

__attribute__((constructor))
static void brshim_init(void) {
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = on_sigsys;
    /* SA_NODEFER: 処理中にもう一度止められても受け取れるようにする */
    sa.sa_flags = SA_SIGINFO | SA_NODEFER;
    sigemptyset(&sa.sa_mask);
    sigaction(SIGSYS, &sa, NULL);
}
