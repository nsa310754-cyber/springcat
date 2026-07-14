"""fixapk — make an old APK installable on a modern Android phone.

No AI, no cloud: a deterministic pipeline that bumps the manifest SDK level
and re-signs the package so recent Android versions stop rejecting it.
"""

from __future__ import annotations

import os
from dataclasses import dataclass

from . import apk, signer

__version__ = "1.0.0"


@dataclass
class FixResult:
    input_path: str
    output_path: str
    repack: "apk.RepackResult"
    sign: "signer.SignResult"
    warnings: list

    def summary(self) -> str:
        r = self.repack
        lines = [f"入力 : {self.input_path}", f"出力 : {self.output_path}"]
        old_t = r.old_target_sdk if r.old_target_sdk is not None else "(未指定)"
        old_m = r.old_min_sdk if r.old_min_sdk is not None else "(未指定)"
        lines.append(f"minSdkVersion    : {old_m}")
        lines.append(f"targetSdkVersion : {old_t} -> {r.new_target_sdk}")
        if r.stripped_signatures:
            lines.append(f"旧署名を除去 : {', '.join(r.stripped_signatures)}")
        lines.append(f"署名方式 : {self.sign.scheme}")
        lines.append(f"署名検証 : {'OK' if self.sign.verified else '失敗'}")
        for w in self.warnings:
            lines.append(f"⚠ {w}")
        return "\n".join(lines)


def default_output_path(input_path: str) -> str:
    base, ext = os.path.splitext(input_path)
    return f"{base}-fixed{ext or '.apk'}"


def fix_apk(
    input_path: str,
    output_path: str | None = None,
    *,
    target_floor: int = apk.INSTALLABLE_TARGET_SDK,
    keystore: str = signer.DEFAULT_KEYSTORE,
) -> FixResult:
    """Convert one old APK into an installable, re-signed APK.

    Raises :class:`apk.ApkError` / :class:`signer.SignError` on failure.
    """
    if not os.path.isfile(input_path):
        raise apk.ApkError(f"file not found: {input_path}")
    if output_path is None:
        output_path = default_output_path(input_path)

    repack = apk.repackage(input_path, output_path, target_floor=target_floor)
    warnings: list = []

    sign_res = signer.sign(
        output_path, need_v2=repack.needs_v2, keystore=keystore
    )

    if repack.needs_v2 and sign_res.scheme == "v1":
        warnings.append(
            f"targetSdkVersion={repack.new_target_sdk} は v2 署名が必須ですが "
            "apksigner が見つからないため v1 でのみ署名しました。"
            "Android 11 以降ではインストールできない可能性があります。"
            "Android SDK build-tools を入れて再実行してください。"
        )
    if not sign_res.verified:
        warnings.append("署名の検証に失敗しました。出力APKを確認してください。")

    return FixResult(
        input_path=input_path,
        output_path=output_path,
        repack=repack,
        sign=sign_res,
        warnings=warnings,
    )
