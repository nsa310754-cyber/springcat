/*
 * br: 実行中のプログラムから Bionic Runner の機能 (画面の上への表示、画面の読み取りとタップ、
 * 通信量の取得、アプリの強制終了) を呼び出すための小さなコマンド。
 *
 *   br overlay set speed "↓ 1.2 MB/s"     画面の上に文字を出す
 *   br ui click "閉じる" "×"              その文字のボタンを探して押す
 *   br tap 540 1200                        画面をタップ
 *   br help                                できることの一覧
 *
 * 中身は、アプリが待ち受けている窓口 (環境変数 BR_API="ホスト:ポート:合言葉") へ引数を送り、
 * 返事を表示するだけ。決まりは単純なので、プログラムから直接つないでもよい:
 *   送る:   合言葉 + 改行、続けて引数をタブで区切った 1 行 (引数の中の \ タブ 改行は \\ \t \n と書く)
 *   受ける: 1 行目が OK か ERR、2 行目以降が結果 (ERR のときは理由)
 * 終了コードは、成功 0、失敗 1、つなげなかったとき 2。
 */
#include <arpa/inet.h>
#include <errno.h>
#include <netinet/in.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

static int send_all(int fd, const char *p, size_t len) {
    while (len > 0) {
        ssize_t n = send(fd, p, len, MSG_NOSIGNAL);
        if (n < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        p += n;
        len -= (size_t) n;
    }
    return 0;
}

int main(int argc, char **argv) {
    const char *env = getenv("BR_API");
    char spec[256];
    if (!env || !*env || strlen(env) >= sizeof(spec)) {
        fprintf(stderr, "br: Bionic Runner のコマンドプロンプトから実行してください (BR_API がありません)\n");
        return 2;
    }
    strcpy(spec, env);
    char *port = strchr(spec, ':');
    char *token = port ? strchr(port + 1, ':') : NULL;
    if (!port || !token) {
        fprintf(stderr, "br: BR_API の形が正しくありません\n");
        return 2;
    }
    *port++ = '\0';
    *token++ = '\0';

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons((unsigned short) atoi(port));
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0 || inet_pton(AF_INET, spec, &addr.sin_addr) != 1
            || connect(fd, (struct sockaddr *) &addr, sizeof(addr)) != 0) {
        fprintf(stderr, "br: アプリにつなげませんでした (Bionic Runner が終了しているかもしれません)\n");
        return 2;
    }

    /* 合言葉の行、続けて引数の行 */
    if (send_all(fd, token, strlen(token)) < 0 || send_all(fd, "\n", 1) < 0) return 2;
    for (int i = 1; i < argc; i++) {
        if (i > 1 && send_all(fd, "\t", 1) < 0) return 2;
        for (const char *p = argv[i]; *p; p++) {
            const char *esc = *p == '\\' ? "\\\\" : *p == '\t' ? "\\t" : *p == '\n' ? "\\n" : NULL;
            if (send_all(fd, esc ? esc : p, esc ? 2 : 1) < 0) return 2;
        }
    }
    if (send_all(fd, "\n", 1) < 0) return 2;
    shutdown(fd, SHUT_WR);

    /* 返事: 1 行目で成否を見て、残りをそのまま出す (失敗のときは標準エラーへ) */
    char buf[8192];
    char head[8];
    size_t have = 0;
    int ok = -1;
    ssize_t n;
    while ((n = recv(fd, buf, sizeof(buf), 0)) != 0) {
        if (n < 0) {
            if (errno == EINTR) continue;
            break;
        }
        char *p = buf;
        size_t left = (size_t) n;
        while (ok < 0 && left > 0) {
            char c = *p++;
            left--;
            if (c == '\n') {
                head[have] = '\0';
                ok = strcmp(head, "OK") == 0 ? 1 : 0;
            } else if (have < sizeof(head) - 1) {
                head[have++] = c;
            }
        }
        if (left > 0) fwrite(p, 1, left, ok == 1 ? stdout : stderr);
    }
    close(fd);
    if (ok < 0) {
        fprintf(stderr, "br: アプリから返事がありませんでした\n");
        return 2;
    }
    return ok == 1 ? 0 : 1;
}
