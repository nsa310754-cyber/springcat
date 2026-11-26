#!/bin/sh
# 専用キーボードのローマ字入力で使う、かな漢字変換の辞書 (SKK-JISYO.L) を取ってきて、アプリで使う形にする。
#   - 取る版は skk-dev/dict のコミットで固定し、ファイルの SHA-256 で照合する (合わなければ止める)
#   - EUC-JP を UTF-8 に直し、注釈 (「;」のあと) とコメントの行を外し、読みの順 (Java の文字列の順) に並べる
#     (キーボードは、この並びの上で二分探索して引く)
#   - 辞書は GPL-2.0 以降。app/src/main/assets/skk/ に、ライセンスの説明と一緒に置く
# 必要なコマンド: curl, python3
set -eu
cd "$(dirname "$0")"
COMMIT=0a164e6b990c5eb5b59eb7d8789f08865dc2f644
SHA256=c791f578d1b4040fce282db29bc22b2cc7ea46f83e269fab2e0fa779e2967e40
OUT=app/src/main/assets/skk
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
curl -fsSL -o "$WORK/SKK-JISYO.L" "https://raw.githubusercontent.com/skk-dev/dict/$COMMIT/SKK-JISYO.L"
echo "$SHA256  $WORK/SKK-JISYO.L" | sha256sum -c -
mkdir -p "$OUT"
python3 - "$WORK/SKK-JISYO.L" "$OUT/SKK-JISYO.L.txt" <<'PY'
import sys
src, dst = sys.argv[1], sys.argv[2]
text = open(src, 'rb').read().decode('euc_jp', errors='strict')
entries = {}
for line in text.split('\n'):
    if not line or line.startswith(';'):
        continue
    sp = line.find(' /')
    if sp <= 0:
        continue
    key = line[:sp]
    cands = []
    for c in line[sp + 2:].split('/'):
        if not c:
            continue
        c = c.split(';', 1)[0]          # 注釈を外す
        if not c or c.startswith('(') or c in cands:
            continue                    # (concat ...) などの式は使わない
        cands.append(c)
    if cands:
        entries.setdefault(key, [])
        for c in cands:
            if c not in entries[key]:
                entries[key].append(c)
# Java の String.compareTo (UTF-16 の順) と同じ並びにする
keys = sorted(entries, key=lambda k: k.encode('utf-16-be'))
with open(dst, 'w', encoding='utf-8', newline='\n') as f:
    for k in keys:
        f.write(k + ' /' + '/'.join(entries[k]) + '/\n')
print('%d 語' % len(keys))
PY
cat > "$OUT/README.txt" <<'TXT'
SKK-JISYO.L.txt: 専用キーボードのローマ字入力の、かな漢字変換の辞書。
元は SKK 辞書 (https://github.com/skk-dev/dict の SKK-JISYO.L、コミット 0a164e6b990c5eb5b59eb7d8789f08865dc2f644、
SHA-256 c791f578d1b4040fce282db29bc22b2cc7ea46f83e269fab2e0fa779e2967e40)。
fetch-skk.sh で EUC-JP から UTF-8 に直し、注釈とコメントを外して、読みの順に並べ替えたもの。

Copyright (C) 1988-1995, 1997, 1999-2014
  Masahiko Sato, Hironobu Takahashi, Masahiro Doteguchi, Miki Inooka, Yukiyoshi Kameyama,
  Akihiko Sasaki, Dai Ando, Junichi Okukawa, Katsushi Sato, Nobuhiro Yamagishi, NAKAJIMA Mikio, MITA Yuusuke,
  SKK Development Team (元のファイルの見出しを参照)

This dictionary is free software; you can redistribute it and/or modify it under the terms of the
GNU General Public License as published by the Free Software Foundation; either version 2, or
(at your option) any later version.
This dictionary is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
See the GNU General Public License for more details (https://www.gnu.org/licenses/old-licenses/gpl-2.0.html).
TXT
ls -la "$OUT"
