"""Command line entry point: ``python -m fixapk old.apk``."""

from __future__ import annotations

import argparse
import sys

from . import __version__, apk, fix_apk, signer


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="fixapk",
        description="古いAPKを最新端末にインストールできる形に変換します(AI不使用)。",
    )
    p.add_argument("input", nargs="+", help="変換する古いAPKファイル(複数可)")
    p.add_argument("-o", "--output", help="出力先(単一ファイル指定時のみ)")
    p.add_argument(
        "--target-sdk",
        type=int,
        default=apk.INSTALLABLE_TARGET_SDK,
        help=f"引き上げる targetSdkVersion の下限(既定: {apk.INSTALLABLE_TARGET_SDK})",
    )
    p.add_argument(
        "--keystore",
        default=signer.DEFAULT_KEYSTORE,
        help="署名に使うキーストア(無ければ自動生成)",
    )
    p.add_argument("--version", action="version", version=f"fixapk {__version__}")
    return p


def main(argv=None) -> int:
    args = build_parser().parse_args(argv)

    if args.output and len(args.input) > 1:
        print("エラー: -o は入力が1ファイルのときだけ指定できます。", file=sys.stderr)
        return 2

    exit_code = 0
    for path in args.input:
        output = args.output if len(args.input) == 1 else None
        print(f"\n=== {path} ===")
        try:
            result = fix_apk(
                path,
                output,
                target_floor=args.target_sdk,
                keystore=args.keystore,
            )
        except (apk.ApkError, signer.SignError) as exc:
            print(f"失敗: {exc}", file=sys.stderr)
            exit_code = 1
            continue
        print(result.summary())
        if result.sign.verified:
            print("\n✅ 完成! この出力APKを端末に転送してインストールしてください。")
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
