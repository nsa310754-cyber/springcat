#!/bin/sh
# Wine の「日本語の環境」(設定で選べる LANG=ja_JP.UTF-8) に使う、glibc のロケール (ja_JP.UTF-8) を作り、
# app/src/main/assets/wine/locale-ja.zip に置く。アプリはこれを files/wine/locale に展開し、
# LOCPATH で glibc に教える (Android には glibc のロケールが無いため)。
# 中身は glibc の localedef が作る、文字の種類・並べ方・日付の形などの表 (約 1.1MB、zip で約 0.3MB)。
# 必要なもの: glibc の localedef とロケールの元データ (Debian / Ubuntu なら locales パッケージ)、python3
# 作った表は、Wine が使う glibc 2.44 で読めることを確かめてある (形は版の間で同じ)。
set -eu
cd "$(dirname "$0")"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
localedef -i ja_JP -f UTF-8 "$work/ja_JP.UTF-8"
python3 - "$work" ../app/src/main/assets/wine/locale-ja.zip <<'PY'
import os, sys, zipfile
src, dst = sys.argv[1], sys.argv[2]
names = []
for root, _, files in os.walk(src):
    for f in files:
        names.append(os.path.relpath(os.path.join(root, f), src))
with zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as z:
    for n in sorted(names):
        info = zipfile.ZipInfo(n, date_time=(2020, 1, 1, 0, 0, 0))  # 毎回同じ中身になるよう日時を固定
        info.compress_type = zipfile.ZIP_DEFLATED
        info.external_attr = 0o644 << 16
        with open(os.path.join(src, n), "rb") as f:
            z.writestr(info, f.read())
PY
ls -l ../app/src/main/assets/wine/locale-ja.zip
