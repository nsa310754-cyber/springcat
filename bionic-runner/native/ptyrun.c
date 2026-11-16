/*
 * ptyrun: 指定したコマンドを疑似端末 (pty) の中で動かし、自分の標準入出力と中継する。
 *
 *   ptyrun <コマンド> [引数...]
 *
 * アプリ (Java) はプロセスとパイプでしか会話できない。パイプ相手だと多くのプログラムは
 * 出力をため込み、入力待ちの文章が終了時まで表示されない。間に本物の端末を挟むことで、
 * 行ごとに出力され、Ctrl-C (0x03 を送る) も効くようになる。
 *
 * 端末を用意できなかった場合は、何も挟まずにコマンドをそのまま実行する (従来どおりの動作)。
 * libc に依存しない方法 (/dev/ptmx と ioctl) だけを使うので bionic でも glibc でもビルドできる。
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

static int write_all(int fd, const char *buf, ssize_t len) {
    while (len > 0) {
        ssize_t n = write(fd, buf, (size_t) len);
        if (n < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        buf += n;
        len -= n;
    }
    return 0;
}

/* master を開き、対になる slave のパスを slave_path に入れる。失敗したら -1 */
static int open_master(char *slave_path, size_t size) {
    int master = open("/dev/ptmx", O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (master < 0) return -1;
    int unlock = 0;
    unsigned int number = 0;
    if (ioctl(master, TIOCSPTLCK, &unlock) != 0 || ioctl(master, TIOCGPTN, &number) != 0) {
        close(master);
        return -1;
    }
    snprintf(slave_path, size, "/dev/pts/%u", number);
    return master;
}

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: ptyrun command [args...]\n");
        return 2;
    }

    char slave_path[64];
    int master = open_master(slave_path, sizeof(slave_path));
    int slave = master < 0 ? -1 : open(slave_path, O_RDWR | O_NOCTTY);
    if (slave < 0) {
        /* 端末なしで続行する */
        if (master >= 0) close(master);
        execvp(argv[1], argv + 1);
        perror(argv[1]);
        return 127;
    }

    /* 端末の大きさ。環境変数 BR_COLS / BR_ROWS があればそれに合わせる (ゲーム画面用) */
    struct winsize ws = {.ws_row = 24, .ws_col = 80};
    const char *cols = getenv("BR_COLS"), *rows = getenv("BR_ROWS");
    if (cols && atoi(cols) >= 10 && atoi(cols) <= 500) ws.ws_col = (unsigned short) atoi(cols);
    if (rows && atoi(rows) >= 5 && atoi(rows) <= 300) ws.ws_row = (unsigned short) atoi(rows);
    ioctl(master, TIOCSWINSZ, &ws);

    pid_t child = fork();
    if (child < 0) {
        perror("fork");
        return 126;
    }
    if (child == 0) {
        /* 新しいセッションを作り、slave をその制御端末にする */
        setsid();
        ioctl(slave, TIOCSCTTY, 0);
        dup2(slave, 0);
        dup2(slave, 1);
        dup2(slave, 2);
        if (slave > 2) close(slave);
        /* ふだんは「画面制御のできない端末」と名乗る。ゲーム画面 (画面制御のできる端末) からは
           BR_TERM で種類を指定してくる */
        const char *term = getenv("BR_TERM");
        setenv("TERM", term && *term ? term : "dumb", 1);
        execvp(argv[1], argv + 1);
        perror(argv[1]);
        _exit(127);
    }
    close(slave);
    signal(SIGPIPE, SIG_IGN);

    char buf[8192];
    int stdin_open = 1;
    int status = 0;
    int child_done = 0;
    for (;;) {
        struct pollfd fds[2];
        fds[0].fd = master;
        fds[0].events = POLLIN;
        fds[1].fd = stdin_open ? 0 : -1;
        fds[1].events = POLLIN;
        /* 子の終了を見逃さないよう、一定間隔でも起きる */
        int ready = poll(fds, 2, 200);
        if (ready < 0 && errno != EINTR) break;

        if (ready > 0 && (fds[0].revents & (POLLIN | POLLHUP | POLLERR))) {
            ssize_t n = read(master, buf, sizeof(buf));
            if (n > 0) {
                if (write_all(1, buf, n) != 0) break; /* 受け手 (アプリ) がいなくなった */
            } else if (n == 0 || errno != EINTR) {
                break; /* slave を開いているプロセスがいなくなった */
            }
        }
        if (ready > 0 && stdin_open && (fds[1].revents & (POLLIN | POLLHUP | POLLERR))) {
            ssize_t n = read(0, buf, sizeof(buf));
            if (n > 0) {
                if (write_all(master, buf, n) != 0) break;
            } else if (n == 0 || errno != EINTR) {
                stdin_open = 0; /* アプリ側が入力を閉じた */
                break;
            }
        }
        if (!child_done && waitpid(child, &status, WNOHANG) == child) {
            child_done = 1;
            /* 残っている出力を取り切ってから終わる */
            fcntl(master, F_SETFL, fcntl(master, F_GETFL) | O_NONBLOCK);
            ssize_t n;
            while ((n = read(master, buf, sizeof(buf))) > 0) {
                if (write_all(1, buf, n) != 0) break;
            }
            break;
        }
    }

    /* master を閉じると、端末につながっていたプロセス全体に SIGHUP が届く */
    close(master);
    if (!child_done) {
        kill(child, SIGHUP);
        /* SIGHUP を無視するプログラムのせいで居残らないよう、少し待って駄目なら強制終了する */
        for (int i = 0; i < 10 && !child_done; i++) {
            if (waitpid(child, &status, WNOHANG) == child) child_done = 1;
            else usleep(100 * 1000);
        }
        if (!child_done) {
            kill(child, SIGKILL);
            waitpid(child, &status, 0);
        }
    }
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return 0;
}
