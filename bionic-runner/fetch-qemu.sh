#!/bin/sh
# QEMU (CPU エミュレータ) を集める。ビルド前に 1 回実行する。
#   - qemu-arm: 32bit の ARM 用プログラムを 64bit 専用の端末で動かす (ユーザーモード)
#   - qemu-system-aarch64: セキュリティ研究用の仮想マシン (Linux をまるごと動かす)
#
# Termux プロジェクトが Android 用 (bionic libc) にビルドした aarch64 版を使う。Android のアプリの
# 中でそのまま動く形式なので、glibc 版のような細工が要らない。
#   - 本体は app/src/main/jniLibs/arm64-v8a/libqemuarm.so と libqemusys.so
#     (実行ファイル。名前は ptyrun と同じ理由)
#   - 本体が読み込む共有ライブラリ一式 (2 つで共有) は app/src/main/assets/qemu/arm64-v8a.zip
#     (名前が lib〜.so の形でないものがあり jniLibs に置けないので、初回に作業領域へ展開する)
# エミュレータが要るのは 64bit の ARM 端末だけなので、arm64-v8a 用だけを用意する。
# Termux の配布物は随時新しい版に置き換わるので、版は固定せず、その時点の一覧
# (署名付きの一覧に載っている SHA-256 で各ファイルを検証する) から取る。取った版は VERSIONS.txt に残す。
# QEMU は GPLv2。同梱のライブラリもそれぞれのライセンス (LGPL など) に従う。
# 必要なコマンド: curl, python3, dpkg-deb, readelf, zip
set -eu
cd "$(dirname "$0")"
OUT=app/src/main/assets/qemu
JNI=app/src/main/jniLibs/arm64-v8a
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$OUT" "$JNI"
ROOT=$(pwd)

cd "$WORK"
python3 - "$ROOT/$OUT" "$ROOT/$JNI" <<'PY'
import gzip, hashlib, os, re, shutil, subprocess, sys, urllib.request

REPO = 'https://packages.termux.dev/apt/termux-main'
out_dir, jni_dir = sys.argv[1], sys.argv[2]
# Android が元から持っているライブラリ (集めない)
SYSTEM = {'libc.so', 'libm.so', 'libdl.so', 'liblog.so', 'libandroid.so'}
# 依存をたどるとき、ライブラリを含まない (実行に関係ない) ので展開しないパッケージ
SKIP = {'python', 'resolv-conf', 'ca-certificates', 'bash', 'coreutils', 'qemu-common'}

def fetch(url):
    return subprocess.check_output(['curl', '-fsSL', url])

index = gzip.decompress(fetch(REPO + '/dists/stable/main/binary-aarch64/Packages.gz')).decode()
packages = {}
for block in index.split('\n\n'):
    fields = dict(re.findall(r'^(\S+): (.*)$', block, re.M))
    if 'Package' in fields:
        packages[fields['Package']] = fields

extracted = {}
def unpack(name):
    """パッケージを取ってきて検証し、展開した場所を返す"""
    if name in extracted:
        return extracted[name]
    p = packages[name]
    data = fetch(REPO + '/' + p['Filename'].replace(':', '%3a'))
    if hashlib.sha256(data).hexdigest() != p['SHA256']:
        sys.exit('SHA-256 が一致しません: ' + name)
    with open(name + '.deb', 'wb') as f:
        f.write(data)
    os.makedirs('x/' + name)
    subprocess.check_call(['dpkg-deb', '-x', name + '.deb', 'x/' + name])
    extracted[name] = 'x/' + name
    return extracted[name]

def needed(path):
    text = subprocess.run(['readelf', '-d', path], capture_output=True, text=True).stdout
    return re.findall(r'NEEDED.*\[(.+?)\]', text)

def depends(name):
    return [d.strip().split()[0] for d in (packages[name].get('Depends') or '').split(',') if d.strip()]

# (パッケージ, 中の実行ファイル, アプリでの名前)
TOOLS = [('qemu-user-arm', 'qemu-arm', 'libqemuarm.so'),
         ('qemu-system-aarch64-headless', 'qemu-system-aarch64', 'libqemusys.so')]
exes = [(unpack(p) + '/data/data/com.termux/files/usr/bin/' + b, out) for p, b, out in TOOLS]
# 依存パッケージを幅優先で展開しながら、本体と各ライブラリが必要とする .so を探していく
available, seen = {}, set()
pending = [(d, 0) for p, _, _ in TOOLS for d in depends(p)]
def scan(name, depth):
    if name in seen or name not in packages or name in SKIP:
        return
    seen.add(name)
    for root, _, files in os.walk(unpack(name)):
        for f in files:
            if '.so' in f and '/lib' in root:
                available.setdefault(f, (os.path.join(root, f), name))
    if depth < 6:
        pending.extend((d, depth + 1) for d in depends(name))

libs, used, queue, done = {}, set(), [l for e, _ in exes for l in needed(e)], set()
while queue:
    lib = queue.pop()
    if lib in done or lib in SYSTEM:
        continue
    done.add(lib)
    while lib not in available and pending:
        scan(*pending.pop(0))
    if lib not in available:
        sys.exit('見つからないライブラリ: ' + lib)
    path, owner = available[lib]
    libs[lib] = os.path.realpath(path)
    used.add(owner)
    queue += needed(libs[lib])

os.makedirs('zip/lib')
for name, path in libs.items():
    shutil.copy(path, 'zip/lib/' + name)
for exe, out in exes:
    shutil.copy(exe, os.path.join(jni_dir, out))
    os.chmod(os.path.join(jni_dir, out), 0o755)
zip_path = os.path.join(out_dir, 'arm64-v8a.zip')
if os.path.exists(zip_path):
    os.remove(zip_path)
subprocess.check_call(['zip', '-q', '-r', '-X', zip_path, 'lib'], cwd='zip')
with open(os.path.join(out_dir, 'VERSIONS.txt'), 'w') as f:
    for name in [p for p, _, _ in TOOLS] + sorted(used):
        f.write('arm64-v8a %s %s (termux)\n' % (name, packages[name]['Version']))
print('qemu %s, ライブラリ %d 個' % (packages['qemu-user-arm']['Version'], len(libs)))
PY
ls -la "$ROOT/$JNI/libqemuarm.so" "$ROOT/$JNI/libqemusys.so" "$ROOT/$OUT/arm64-v8a.zip"
cat "$ROOT/$OUT/VERSIONS.txt"
