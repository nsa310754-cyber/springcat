"""End-to-end test of the whole pipeline using only the JDK.

Builds a fake "old" APK (patched-format manifest + dummy dex + stale
signature files), runs :func:`fixapk.fix_apk`, and asserts the result is
manifest-bumped, signature-stripped and jarsigner-verifiable.
"""

import os
import shutil
import sys
import tempfile
import zipfile

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from fixapk import apk, axml, fix_apk
from tests.test_axml import build_manifest


def _make_fake_apk(path: str, min_sdk: int, target_sdk):
    manifest = build_manifest(min_sdk=min_sdk, target_sdk=target_sdk)
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("AndroidManifest.xml", manifest)
        z.writestr("classes.dex", b"dex\n035\x00" + b"\x00" * 64)
        z.writestr("resources.arsc", b"\x02\x00\x0c\x00" + b"\x00" * 32)
        # stale signature that must be stripped
        z.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n")
        z.writestr("META-INF/CERT.SF", "Signature-Version: 1.0\n")
        z.writestr("META-INF/CERT.RSA", b"\x00\x01\x02fakecert")


def test_full_pipeline():
    workdir = tempfile.mkdtemp(prefix="fixapk-test-")
    try:
        src = os.path.join(workdir, "old.apk")
        dst = os.path.join(workdir, "old-fixed.apk")
        keystore = os.path.join(workdir, "ks.keystore")
        _make_fake_apk(src, min_sdk=8, target_sdk=10)

        result = fix_apk(src, dst, keystore=keystore)

        # manifest bumped to the installable floor
        assert result.repack.new_target_sdk == apk.INSTALLABLE_TARGET_SDK
        with zipfile.ZipFile(dst) as z:
            names = z.namelist()
            versions = axml.read_sdk_versions(z.read("AndroidManifest.xml"))
        assert versions["targetSdkVersion"] == apk.INSTALLABLE_TARGET_SDK, versions

        # old signature stripped, fresh one added, and dex/arsc preserved
        assert "META-INF/CERT.SF" not in names
        assert "META-INF/CERT.RSA" not in names
        assert "classes.dex" in names
        assert "resources.arsc" in names
        assert any(n.endswith(".RSA") or n.endswith(".EC") for n in names), names

        # jarsigner accepted the signature
        assert result.sign.scheme == "v1", result.sign.scheme
        assert result.sign.verified, "jarsigner -verify should pass"
        assert not result.warnings, result.warnings

        print(result.summary())
    finally:
        shutil.rmtree(workdir, ignore_errors=True)


def test_min_only_apk_bumps_min():
    workdir = tempfile.mkdtemp(prefix="fixapk-test-")
    try:
        src = os.path.join(workdir, "old.apk")
        dst = os.path.join(workdir, "old-fixed.apk")
        keystore = os.path.join(workdir, "ks.keystore")
        _make_fake_apk(src, min_sdk=9, target_sdk=None)

        result = fix_apk(src, dst, keystore=keystore)
        with zipfile.ZipFile(dst) as z:
            versions = axml.read_sdk_versions(z.read("AndroidManifest.xml"))
        # no target attribute existed, so min was bumped to raise effective target
        assert versions["minSdkVersion"] == apk.INSTALLABLE_TARGET_SDK, versions
        assert result.sign.verified
    finally:
        shutil.rmtree(workdir, ignore_errors=True)


if __name__ == "__main__":
    test_full_pipeline()
    print("ok  test_full_pipeline")
    test_min_only_apk_bumps_min()
    print("ok  test_min_only_apk_bumps_min")
    print("all end-to-end tests passed")
