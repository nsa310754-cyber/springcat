#!/bin/bash
# Bionic Runner PC — Linux 側のセットアップ (WSL ディストリの中で root として実行される)
#
# 作るもの:
#   glibc   … Ubuntu 24.04 そのもの (ディストリの土台)。apt でソフトを足せる。
#   bionic  … /system に Android 9 の bionic (linker64 / libc.so 等) を置く。
#             Android 用にビルドされた ELF がそのまま起動する。
#   Termux  … /data/data/com.termux/files/usr に Termux の環境一式 (bionic で動く
#             bash・apt・pkg)。`termux` コマンドで入る。
#   他の CPU … qemu-user で、x86_64 PC なら ARM64 / ARM の Android・Linux 用 ELF も動かす。
#
# 何度実行してもよい (済んでいる手順は飛ばす)。ISO イメージは一切使わない:
# 入手するのは Ubuntu の rootfs (tar.gz)、Termux の .deb / bootstrap.zip、apt のパッケージのみ。
#
# 行頭が "@@" の行は Windows 側の画面が進捗表示に使う目印。

set -euo pipefail
export DEBIAN_FRONTEND=noninteractive LANG=C.UTF-8 LC_ALL=C.UTF-8

SETUP_VERSION=2
BR=/opt/bionic-runner
TERMUX_APP=/data/data/com.termux
ROOTFS=$TERMUX_APP/files
PREFIX=$ROOTFS/usr
THOME=$ROOTFS/home
TERMUX_REPO=https://packages-cf.termux.dev/apt/termux-main
TERMUX_BOOTSTRAP_LATEST=https://github.com/termux/termux-packages/releases/latest/download
TERMUX_BOOTSTRAP_PINNED=https://github.com/termux/termux-packages/releases/download/bootstrap-2026.11.15-r1%2Bapt.android-7
UBUNTU_BASE=https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release
WORK=/var/cache/bionic-runner
TUID=1000
LUSER=user   # 一般ユーザー (uid 1000)。Windows の管理者で起動したときだけ root を使う

step() { echo "@@STEP $*"; }
info() { echo "  $*"; }
die()  { echo "@@ERROR $*"; exit 1; }
trap 'echo "@@ERROR ${BASH_COMMAND} で失敗しました (行 $LINENO)"' ERR

fetch() { # fetch URL 保存先 — 失敗したら 3 回まで再試行
	curl -fL --retry 3 --retry-delay 3 --connect-timeout 30 -sS -o "$2.part" "$1"
	mv "$2.part" "$2"
}

HOST=$(uname -m)
case "$HOST" in
	x86_64)  FOREIGN=aarch64 ; UB_FOREIGN=arm64 ;;
	aarch64) FOREIGN=x86_64  ; UB_FOREIGN=amd64 ;;
	*) die "この CPU ($HOST) には対応していません (x86_64 / aarch64 のみ)" ;;
esac

mkdir -p "$BR/bin" "$BR/sysroot" "$WORK"

# ---------------------------------------------------------------- 1. 基本設定
step "1/7 WSL の設定"
cat > /etc/wsl.conf <<'EOF'
# Bionic Runner PC が作成
[boot]
systemd=false

[user]
# 普段は一般ユーザー ($)。root (#) は Bionic Runner PC を「管理者として実行」したとき
default=user

[interop]
# Windows 側の PATH を混ぜない (Linux のコマンドが Windows の同名コマンドに負けないように)
appendWindowsPath=false
EOF

cat > /etc/profile.d/bionic-runner.sh <<EOF
# Bionic Runner PC
export PATH="$BR/bin:\$PATH"
export LANG=\${LANG:-C.UTF-8}
EOF
echo "bionic-runner" > /etc/hostname 2>/dev/null || true

# ------------------------------------------------------- 2. glibc 側のパッケージ
step "2/7 Ubuntu (glibc) のパッケージを入れています"
PKGS="ca-certificates curl unzip xz-utils file procps psmisc less nano musl qemu-user-static"
need=""
for p in $PKGS; do dpkg -s "$p" >/dev/null 2>&1 || need="$need $p"; done
if [ -n "$need" ]; then
	apt-get update -qq
	# shellcheck disable=SC2086
	apt-get install -y -qq -o Dpkg::Use-Pty=0 --no-install-recommends $need
	apt-get clean
fi
info "$(getconf GNU_LIBC_VERSION) / musl / qemu-user"

# ------------------------------------------------- Termux パッケージ解決の下準備
declare -A PKG_FILE PKG_DEPS
read_index() { # read_index arch — Termux の Packages 一覧から名前→ファイル・依存を読む
	local arch=$1 idx="$WORK/Packages-$1"
	PKG_FILE=(); PKG_DEPS=()
	if [ ! -s "$idx" ] || [ -n "$(find "$idx" -mmin +720 2>/dev/null)" ]; then
		fetch "$TERMUX_REPO/dists/stable/main/binary-$arch/Packages" "$idx"
	fi
	local name file deps
	while IFS=$'\t' read -r name file deps; do
		PKG_FILE[$name]=$file
		PKG_DEPS[$name]=$deps
	done < <(awk '
		/^Package: /  { n=$2 }
		/^Filename: / { f=$2 }
		/^Depends: /  { sub(/^Depends: /, ""); d=$0 }
		/^$/ { if (n != "") print n "\t" f "\t" d; n=""; f=""; d="" }
		END  { if (n != "") print n "\t" f "\t" d }' "$idx")
}

dep_names() { # "a (>= 1), b | c" → "a b"
	echo "$1" | tr ',' '\n' | sed -E 's/\|.*//; s/\(.*\)//; s/[[:space:]]//g' | grep -v '^$' || true
}

get_deb() { # get_deb arch name → .deb のパスを表示
	local file=${PKG_FILE[$2]:-}
	[ -n "$file" ] || die "Termux のパッケージ $2 ($1) が見つかりません"
	local out="$WORK/$1-$(basename "$file")"
	[ -s "$out" ] || fetch "$TERMUX_REPO/$file" "$out"
	echo "$out"
}

# Termux の dpkg に「インストール済み」と記録しながら展開する (termux-docker と同じやり方)
declare -A DONE
termux_install() { # termux_install name
	local name=$1 dep deb tmp
	[ -n "${DONE[$name]:-}" ] && return 0
	DONE[$name]=1
	if grep -qx "Package: $name" "$PREFIX/var/lib/dpkg/status" 2>/dev/null; then return 0; fi
	for dep in $(dep_names "${PKG_DEPS[$name]:-}"); do termux_install "$dep"; done
	info "Termux: $name"
	deb=$(get_deb "$HOST" "$name")
	dpkg-deb -x "$deb" /
	tmp=$(mktemp -d)
	dpkg-deb -e "$deb" "$tmp"
	dpkg-deb -c "$deb" | awk '{ p=$6; sub(/^\./, "", p); if (p == "/") p="/."; sub(/\/$/, "", p); print p }' \
		> "$PREFIX/var/lib/dpkg/info/$name.list"
	{ cat "$tmp/control"; echo "Status: install ok installed"; echo; } >> "$PREFIX/var/lib/dpkg/status"
	for f in conffiles postinst postrm preinst prerm; do
		[ -f "$tmp/$f" ] && cp "$tmp/$f" "$PREFIX/var/lib/dpkg/info/$name.$f"
	done
	rm -rf "$tmp"
}

# ------------------------------------------------- 3. Termux bootstrap (bionic)
step "3/7 Termux の基本環境 (bionic) を展開しています"
if [ ! -x "$PREFIX/bin/bash" ]; then
	zip="$WORK/bootstrap-$HOST.zip"
	if [ ! -s "$zip" ]; then
		fetch "$TERMUX_BOOTSTRAP_LATEST/bootstrap-$HOST.zip" "$zip" \
			|| fetch "$TERMUX_BOOTSTRAP_PINNED/bootstrap-$HOST.zip" "$zip"
	fi
	rm -rf "$PREFIX"
	mkdir -p "$PREFIX"
	unzip -q "$zip" -d "$PREFIX"
	( cd "$PREFIX"
	  while IFS= read -r line; do
		dest=${line%%←*}; link=${line#*←}
		mkdir -p "$(dirname "$link")"
		ln -sfn "$dest" "$link"
	  done < SYMLINKS.txt
	  rm -f SYMLINKS.txt )
	chmod -R u+rwX "$PREFIX"
	find "$PREFIX/bin" "$PREFIX/libexec" -type f -exec chmod 755 {} + 2>/dev/null || true
	[ -d "$PREFIX/lib/apt" ] && find "$PREFIX/lib/apt" -type f -exec chmod 755 {} +
	rm -f "$zip"
fi
mkdir -p "$THOME" "$PREFIX/tmp" "$TERMUX_APP/cache"

# -------------------------------------------------- 4. bionic (/system) を置く
step "4/7 Android の bionic (linker64 / libc.so) を入れています"
read_index "$HOST"
termux_install aosp-libs
termux_install aosp-utils
termux_install libandroid-stub
if [ ! -L /system ]; then
	rm -rf /system
	ln -s "data/data/com.termux/files/usr/opt/aosp" /system
fi
[ -x /system/bin/linker64 ] || die "/system/bin/linker64 がありません"
# 一般ユーザー (uid 1000)。Linux の端末ではこのユーザー ($)、Termux もこの uid で動かす。
# termux-docker と同様、Termux 側の apt で /system を更新できるよう Termux 一式はこの uid の持ち物にする
if getent passwd termux >/dev/null; then   # セットアップ v1 で作った名前を変える
	usermod -l $LUSER -d /home/$LUSER -s /bin/bash termux
	groupmod -n $LUSER termux
fi
getent group $LUSER >/dev/null || groupadd -g $TUID $LUSER
getent passwd $LUSER >/dev/null || useradd -u $TUID -g $TUID -d /home/$LUSER -s /bin/bash -M $LUSER
mkdir -p /home/$LUSER
[ -e /home/$LUSER/.profile ] || cp -a /etc/skel/. /home/$LUSER/ 2>/dev/null || true
chown -R $TUID:$TUID /home/$LUSER "$ROOTFS" "$TERMUX_APP/cache"
chmod 755 /data /data/data "$TERMUX_APP"
find -L /system -type f -perm /111 -exec chmod 755 {} + 2>/dev/null || true

# ------------------------------------------- 5. 他の CPU 用 (qemu-user + sysroot)
step "5/7 他の CPU ($FOREIGN) 用の環境を入れています"
SR="$BR/sysroot/$FOREIGN"
if [ ! -e "$SR/.ready" ]; then
	rm -rf "$SR"; mkdir -p "$SR"
	# glibc: 同じ Ubuntu の rootfs (tar.gz) を、その CPU 用にもう 1 つ置くだけ
	tgz=$(curl -fsSL "$UBUNTU_BASE/" | grep -oE "ubuntu-base-24\.04(\.[0-9]+)?-base-$UB_FOREIGN\.tar\.gz" | sort -uV | tail -n1)
	[ -n "$tgz" ] || die "Ubuntu base ($UB_FOREIGN) が見つかりません"
	fetch "$UBUNTU_BASE/$tgz" "$WORK/$tgz"
	want=$(curl -fsSL "$UBUNTU_BASE/SHA256SUMS" | awk -v f="$tgz" '{ g=$2; sub(/^\*/, "", g) } g == f { print $1 }')
	got=$(sha256sum "$WORK/$tgz" | awk '{print $1}')
	[ -n "$want" ] && [ "$want" = "$got" ] || die "$tgz のチェックサムが一致しません"
	tar -xzf "$WORK/$tgz" -C "$SR" --exclude='./dev/*'
	rm -f "$WORK/$tgz"
	# bionic: Termux の aosp-libs / aosp-utils (その CPU 用) を sysroot の /system に置く
	read_index "$FOREIGN"
	for p in aosp-libs aosp-utils; do
		x=$(mktemp -d)
		dpkg-deb -x "$(get_deb "$FOREIGN" "$p")" "$x"
		mkdir -p "$SR/system"
		cp -a "$x$PREFIX/opt/aosp/." "$SR/system/"
		rm -rf "$x"
	done
	touch "$SR/.ready"
	read_index "$HOST"
fi
rm -f "$WORK"/*.deb

# --------------------------------------------------------- 6. 便利コマンド
step "6/7 コマンド (termux / brun) を作っています"
cat > /usr/local/bin/termux <<EOF
#!/bin/sh
# termux [--root] [コマンド…]
#   Termux (bionic) の環境に入る。引数があればそのコマンドを Termux 側で実行する。
#   ふだんは一般ユーザー (uid $TUID、プロンプト \$) で動く。root から呼ぶと自動で uid $TUID に切り替える
#   (Termux の pkg / apt は root では動かないため)。
#   --root を付けると root のまま入る (プロンプト #)。この場合 pkg / apt は使えない。
P=$PREFIX
asroot=
if [ "\${1-}" = --root ]; then asroot=1; shift; fi
me=\$(id -u)
if [ "\$me" != 0 ] && [ "\$me" != $TUID ]; then
	echo "termux: uid \$me からは使えません (root か $LUSER で実行してください)" >&2; exit 1
fi
if [ -n "\$asroot" ] && [ "\$me" != 0 ]; then
	echo "termux: --root は root (Bionic Runner PC を管理者として実行) のときだけ使えます" >&2; exit 1
fi
if grep -q '^nameserver' /etc/resolv.conf 2>/dev/null && [ -w "\$P/etc/resolv.conf" -o "\$me" = 0 ]; then
	# bionic は /etc/resolv.conf を読まないので、WSL の DNS 設定を Termux 側へ写す
	grep '^nameserver' /etc/resolv.conf > "\$P/etc/resolv.conf" 2>/dev/null || true
fi
[ \$# -eq 0 ] && set -- "\$P/bin/login"
cd "$THOME" 2>/dev/null || cd /
set -- env -i ANDROID_DATA=/data ANDROID_ROOT=/system HOME="$THOME" \
	LANG=en_US.UTF-8 PATH="\$P/bin" PREFIX="\$P" TMPDIR="\$P/tmp" \
	TERM="\${TERM:-xterm-256color}" TZ="\${TZ:-UTC}" "\$@"
if [ "\$me" = 0 ] && [ -z "\$asroot" ]; then
	exec setpriv --reuid=$TUID --regid=$TUID --clear-groups "\$@"
fi
if [ -n "\$asroot" ]; then
	# root で作られたファイルが残ると、あとで一般ユーザーの pkg が書き込めなくなるので持ち主を戻す
	"\$@"; rc=\$?
	find "$ROOTFS" -xdev -uid 0 -exec chown -h $TUID:$TUID {} + 2>/dev/null
	exit \$rc
fi
exec "\$@"
EOF
chmod 755 /usr/local/bin/termux

# Termux の初回ログイン時の処理 (bootstrap の後半) を、一般ユーザーで先に済ませておく。
# root で最初にログインすると root の持ち物のファイルができて pkg が壊れるため
SECOND="$PREFIX/etc/termux/termux-bootstrap/second-stage/termux-bootstrap-second-stage.sh"
if [ -x "$SECOND" ] && [ ! -e "$SECOND.lock" ] && [ ! -L "$SECOND.lock" ]; then
	/usr/local/bin/termux "$PREFIX/bin/bash" "$SECOND" >/dev/null 2>&1 || info "Termux の初期化の一部に失敗しました"
fi
chown -R $TUID:$TUID "$ROOTFS"

cat > /usr/local/bin/brun <<EOF
#!/bin/sh
# brun ファイル [引数…] — ELF の CPU を見て、この PC の CPU と違えば qemu で動かす
[ \$# -ge 1 ] || { echo "使い方: brun 実行ファイル [引数…]" >&2; exit 2; }
f=\$1
[ -e "\$f" ] || f=\$(command -v "\$1") || { echo "brun: \$1 が見つかりません" >&2; exit 127; }
shift
m=\$(od -An -tu2 -j18 -N2 "\$f" 2>/dev/null | tr -d ' ')
case "\$m" in
	62)  a=x86_64 ;;  3) a=i386 ;;  183) a=aarch64 ;;  40) a=arm ;;  *) a="" ;;
esac
case "$HOST:\$a" in
	x86_64:x86_64|x86_64:i386|aarch64:aarch64|:*|*:) exec "\$f" "\$@" ;;
	x86_64:arm)       exec qemu-arm-static     -L $BR/sysroot/aarch64 "\$f" "\$@" ;;
	x86_64:aarch64)   exec qemu-aarch64-static -L $BR/sysroot/aarch64 "\$f" "\$@" ;;
	aarch64:x86_64)   exec qemu-x86_64-static  -L $BR/sysroot/x86_64  "\$f" "\$@" ;;
	aarch64:i386)     exec qemu-i386-static    -L $BR/sysroot/x86_64  "\$f" "\$@" ;;
	aarch64:arm)      exec qemu-arm-static     -L $BR/sysroot/x86_64  "\$f" "\$@" ;;
esac
EOF
chmod 755 /usr/local/bin/brun

# ------------------------------------------------------------- 7. 動作確認
step "7/7 動作確認"
ok=1
check() { # check 説明 コマンド…
	local d=$1; shift
	if out=$("$@" 2>&1); then info "✔ $d: $(echo "$out" | head -n1)"; else info "✖ $d: $(echo "$out" | head -n1)"; ok=0; fi
}
check "glibc (Ubuntu)"          /bin/uname -m
check "bionic (/system/bin)"    /system/bin/toybox uname -m
check "Termux (bionic の bash)" /usr/local/bin/termux "$PREFIX/bin/bash" -c 'echo bash $BASH_VERSION'
check "一般ユーザー ($LUSER)"   setpriv --reuid=$TUID --regid=$TUID --clear-groups /bin/sh -c 'echo "uid $(id -u)"'
check "他の CPU: bionic ($FOREIGN)" qemu-$FOREIGN-static -L "$SR" "$SR/system/bin/toybox" uname -m
check "他の CPU: glibc ($FOREIGN)"  qemu-$FOREIGN-static -L "$SR" "$SR/usr/bin/uname" -m

echo "$SETUP_VERSION" > "$BR/.setup-version"
[ "$ok" = 1 ] || echo "@@WARN 一部の確認に失敗しました (上の ✖ を見てください)"
echo "@@DONE"
