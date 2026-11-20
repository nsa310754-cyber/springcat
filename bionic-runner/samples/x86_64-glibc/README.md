# x86_64 の glibc 用プログラムのサンプル (Box64 と QEMU の比べ方)

`primes-x86_64` は、PC 用の 64bit (x86_64) の Linux のプログラムで、glibc を動的リンクしたもの。
指定した数までの素数を数える。Box64 (QEMU より速い) が入った Bionic Runner では Box64 で、
入っていなければ QEMU で動く。

## 動かし方

1. エクスプローラーや「実行ファイル」から `primes-x86_64` を選び、登録する
   (登録名は `primes-x86_64.Gelf` になる)
2. コマンドプロンプトで:

```
primes-x86_64.Gelf 3000000
→ 3000000 までの素数: 216816 個 (x86_64, glibc 2.44-0)
```

## Box64 と QEMU の速さを比べる

```
bash bench.sh primes-x86_64.Gelf 3000000
```

起動スクリプトの環境変数 `BR_X86` で、その 1 回だけ実行方法を選んで (`box64` / `qemu`)、同じ計算の
時間を出す。Box64 が使えない端末では、Box64 の行に理由が付いて QEMU の時間になる。
ふだんの実行方法は、設定 → 個人設定 →「x86_64 の実行方法」で選ぶ。

## ビルドのしかた

x86_64 の Linux の PC で (glibc 2.44 より新しい環境ではビルドしないこと):

```sh
gcc -O2 -o primes-x86_64 primes.c
```

ARM の PC などでは、クロスコンパイラ `x86_64-linux-gnu-gcc -O2 -o primes-x86_64 primes.c` を使う。
同梱の `primes-x86_64` は Ubuntu 24.04 (glibc 2.39) の gcc でビルドしたもの (必要な glibc は 2.34 まで)。
