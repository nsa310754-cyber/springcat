// Block Destroy — ネイティブ実行ファイル版 (Android = Bionic / Linux = glibc。arm64 など)
//   ゲーム本体 (game.html) と html2canvas を gzip で中に持った、小さな Web サーバー。
//   実行すると http://127.0.0.1:<ポート>/ でゲームを配信し、ブラウザで開く
//   (Android は am start / Linux は xdg-open。開けない時は表示された URL をブラウザに入れる)。
//   ゲームはブラウザの中で動くので、読み込んだ後はこのプログラムを閉じても遊べる (セーブはブラウザに残る)。
//   使い方: bdserve [--port N] [--no-open] [--lan]
//     --port N   使うポート (既定 8765。使用中なら次の番号を順に試す)
//     --no-open  ブラウザを自動で開かない
//     --lan      同じ Wi-Fi の他の端末からも開けるようにする (0.0.0.0 で待つ)
//   http://127.0.0.1:<ポート>/quit を開くとサーバーを終了する。
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/types.h>
#include <unistd.h>

#ifdef __ANDROID__
// Android (Bionic) の静的リンクでは、TLS (スレッドごとの領域) の並びが 64 バイト以上でないと
// 起動時に「TLS segment is underaligned」で止まる (arm64)。64 バイトにそろえた TLS 変数を1つ置いて合わせる。
__thread char bd_tls_align[64] __attribute__((aligned(64)));
#endif
extern const unsigned char bd_game_gz[];
extern const unsigned long bd_game_gz_len;
extern const unsigned char bd_h2c_gz[];
extern const unsigned long bd_h2c_gz_len;
extern const char bd_version[];

static void send_all(int fd, const void *p, size_t n) {
  const char *s = (const char *)p;
  while (n > 0) {
    ssize_t w = send(fd, s, n, 0);
    if (w < 0) { if (errno == EINTR) continue; return; }
    s += w; n -= (size_t)w;
  }
}

static void reply(int fd, const char *status, const char *type, const void *body, unsigned long len, int gz) {
  char h[512];
  int n = snprintf(h, sizeof h,
    "HTTP/1.1 %s\r\nContent-Type: %s\r\n%sContent-Length: %lu\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n",
    status, type, gz ? "Content-Encoding: gzip\r\n" : "", len);
  send_all(fd, h, (size_t)n);
  if (body && len) send_all(fd, body, len);
}

// ブラウザで開く (失敗しても何もしない)
static void open_browser(const char *url) {
  pid_t p = fork();
  if (p != 0) return;
  int nul = open("/dev/null", O_RDWR);
  if (nul >= 0) { dup2(nul, 0); dup2(nul, 1); dup2(nul, 2); }
  if (access("/system/bin/am", X_OK) == 0)
    execl("/system/bin/am", "am", "start", "-a", "android.intent.action.VIEW", "-d", url, (char *)NULL);
  execlp("xdg-open", "xdg-open", url, (char *)NULL);
  _exit(0);
}

// 1つのリクエストに答える。/quit なら 1 を返す
static int handle(int fd) {
  char buf[8192];
  size_t got = 0;
  struct timeval tv = { 5, 0 };
  setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv);
  while (got < sizeof buf - 1) {
    ssize_t r = recv(fd, buf + got, sizeof buf - 1 - got, 0);
    if (r <= 0) break;
    got += (size_t)r;
    buf[got] = 0;
    if (strstr(buf, "\r\n\r\n")) break;
  }
  buf[got] = 0;
  char method[16] = "", path[1024] = "";
  if (sscanf(buf, "%15s %1023s", method, path) != 2) return 0;
  char *q = strchr(path, '?'); if (q) *q = 0;
  int head = strcmp(method, "HEAD") == 0;
  if (strcmp(method, "GET") != 0 && !head) { reply(fd, "405 Method Not Allowed", "text/plain", "", 0, 0); return 0; }
  if (!strcmp(path, "/") || !strcmp(path, "/index.html") || !strcmp(path, "/game.html")) {
    reply(fd, "200 OK", "text/html; charset=utf-8", head ? NULL : bd_game_gz, bd_game_gz_len, 1);
  } else if (!strcmp(path, "/html2canvas.min.js")) {
    reply(fd, "200 OK", "application/javascript; charset=utf-8", head ? NULL : bd_h2c_gz, bd_h2c_gz_len, 1);
  } else if (!strcmp(path, "/quit")) {
    const char *m = "<meta charset=utf-8><p style=\"font:16px sans-serif;padding:20px\">Block Destroy サーバーを終了しました。</p>";
    reply(fd, "200 OK", "text/html; charset=utf-8", m, strlen(m), 0);
    return 1;
  } else {
    reply(fd, "404 Not Found", "text/plain", "not found", 9, 0);
  }
  return 0;
}

int main(int argc, char **argv) {
#ifdef __ANDROID__
  bd_tls_align[0] = 0;
#endif
  int port = 8765, auto_open = 1, lan = 0;
  for (int i = 1; i < argc; i++) {
    if (!strcmp(argv[i], "--port") && i + 1 < argc) port = atoi(argv[++i]);
    else if (!strcmp(argv[i], "--no-open")) auto_open = 0;
    else if (!strcmp(argv[i], "--lan")) lan = 1;
    else if (!strcmp(argv[i], "--version")) { printf("Block Destroy %s\n", bd_version); return 0; }
    else if (!strcmp(argv[i], "--help") || !strcmp(argv[i], "-h")) {
      printf("使い方: %s [--port N] [--no-open] [--lan]\n", argv[0]);
      return 0;
    }
  }
  signal(SIGPIPE, SIG_IGN);
  signal(SIGCHLD, SIG_IGN);   // ブラウザを開いた子プロセスを残さない
  setvbuf(stdout, NULL, _IONBF, 0);

  int s = socket(AF_INET, SOCK_STREAM, 0);
  if (s < 0) { perror("socket"); return 1; }
  int one = 1;
  setsockopt(s, SOL_SOCKET, SO_REUSEADDR, &one, sizeof one);
  struct sockaddr_in a;
  memset(&a, 0, sizeof a);
  a.sin_family = AF_INET;
  a.sin_addr.s_addr = htonl(lan ? INADDR_ANY : INADDR_LOOPBACK);
  int bound = 0;
  for (int k = 0; k < 30 && !bound; k++) {
    a.sin_port = htons((unsigned short)(port + k));
    if (bind(s, (struct sockaddr *)&a, sizeof a) == 0) { port += k; bound = 1; }
  }
  if (!bound || listen(s, 16) != 0) { perror("bind/listen"); return 1; }

  char url[64];
  snprintf(url, sizeof url, "http://127.0.0.1:%d/", port);
  printf("🧱 Block Destroy %s\n", bd_version);
  printf("ゲームを配信しています: %s\n", url);
  printf("ブラウザ (Chrome など) でこの URL を開くと遊べます。\n");
  printf("読み込んだ後はこのプログラムを閉じても遊べます (セーブはブラウザに残ります)。\n");
  if (lan) printf("--lan: 同じ Wi-Fi の他の端末からは http://<この端末のIP>:%d/ で開けます。\n", port);
  printf("終了: Ctrl+C か %squit を開く\n", url);
  if (auto_open) open_browser(url);

  for (;;) {
    int c = accept(s, NULL, NULL);
    if (c < 0) { if (errno == EINTR) continue; perror("accept"); continue; }
    int quit = handle(c);
    shutdown(c, SHUT_WR);
    close(c);
    if (quit) { printf("終了しました。\n"); break; }
  }
  close(s);
  return 0;
}
