#!/bin/sh
# QEMU (CPU エミュレータ) を集める。ビルド前に 1 回実行する。
#   - qemu-arm: 32bit の ARM 用プログラムを 64bit 専用の端末で動かす (ユーザーモード)
#   - qemu-x86_64・qemu-i386: PC 用 (64bit / 32bit) の Linux のプログラムを ARM の端末で動かす
#   - qemu-riscv64・qemu-riscv32・qemu-ppc・qemu-ppc64・qemu-m68k: そのほかの CPU 用
#   (Termux にあるユーザーモードのエミュレータはすべて入れる。qemu-aarch64 だけは、この端末自身の
#    CPU 用なので要らない)
#   - qemu-system-aarch64・qemu-system-x86_64: セキュリティ研究用の仮想マシン (Linux をまるごと動かす)
#
# Termux プロジェクトが Android 用 (bionic libc) にビルドした aarch64 版を使う。Android のアプリの
# 中でそのまま動く形式なので、glibc 版のような細工が要らない。
#   - 本体は app/src/main/jniLibs/arm64-v8a/libqemu〜.so (実行ファイル。名前は ptyrun と同じ理由)
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
         ('qemu-user-x86-64', 'qemu-x86_64', 'libqemux8664.so'),
         ('qemu-user-i386', 'qemu-i386', 'libqemui386.so'),
         ('qemu-user-riscv64', 'qemu-riscv64', 'libqemuriscv64.so'),
         ('qemu-user-riscv32', 'qemu-riscv32', 'libqemuriscv32.so'),
         ('qemu-user-ppc', 'qemu-ppc', 'libqemuppc.so'),
         ('qemu-user-ppc64', 'qemu-ppc64', 'libqemuppc64.so'),
         ('qemu-user-m68k', 'qemu-m68k', 'libqemum68k.so'),
         ('qemu-system-aarch64-headless', 'qemu-system-aarch64', 'libqemusys.so'),
         ('qemu-system-x86-64-headless', 'qemu-system-x86_64', 'libqemusysx86.so')]
# x86_64 の仮想マシンが起動に使う部品 (BIOS と、カーネルを直接起動するための小さなプログラム)。
# zip の fw/ に入れる。ARM の仮想マシンはカーネルを直接起動するので、部品は要らない
# (ただし VNC の画面を使う Windows の仮想マシンは、fw/keymaps/ のキーボードの対応表を使う)
FIRMWARE = ['bios-256k.bin', 'linuxboot_dma.bin', 'kvmvapic.bin']
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
os.makedirs('zip/fw')
share = unpack('qemu-common') + '/data/data/com.termux/files/usr/share/qemu/'
for name in FIRMWARE:
    shutil.copy(share + name, 'zip/fw/' + name)
# VNC がキー (X の keysym) を PC のキーの番号に直すための対応表 (Windows の仮想マシンの画面で使う)
shutil.copytree(share + 'keymaps', 'zip/fw/keymaps')
used.add('qemu-common')
for exe, out in exes:
    shutil.copy(exe, os.path.join(jni_dir, out))
    os.chmod(os.path.join(jni_dir, out), 0o755)
zip_path = os.path.join(out_dir, 'arm64-v8a.zip')
if os.path.exists(zip_path):
    os.remove(zip_path)
subprocess.check_call(['zip', '-q', '-r', '-X', zip_path, 'lib', 'fw'], cwd='zip')
with open(os.path.join(out_dir, 'VERSIONS.txt'), 'w') as f:
    for name in [p for p, _, _ in TOOLS] + sorted(used):
        f.write('arm64-v8a %s %s (termux)\n' % (name, packages[name]['Version']))
    f.write('arm64-v8a keymaps (VNC のキーボードの対応表、qemu-common %s から) (termux)\n'
            % packages['qemu-common']['Version'])
print('qemu %s, ライブラリ %d 個' % (packages['qemu-user-arm']['Version'], len(libs)))
PY
ls -la "$ROOT/$JNI"/libqemu*.so "$ROOT/$OUT/arm64-v8a.zip"
cat "$ROOT/$OUT/VERSIONS.txt"
