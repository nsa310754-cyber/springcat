"""Re-sign an APK using nothing but a JDK (keytool + jarsigner).

For an app whose targetSdkVersion is below 30, a v1 (JAR) signature alone is
accepted by every Android version, including 14/15, so ``jarsigner`` — which
ships with the JDK — is enough and no Android SDK is required.

When the app targets 30+ (needs APK Signature Scheme v2), we transparently use
``apksigner`` from the Android SDK if it can be found; otherwise we fall back
to v1 with a clear warning.
"""

from __future__ import annotations

import os
import shutil
import subprocess
from dataclasses import dataclass

DEFAULT_KEYSTORE = os.path.join(os.path.expanduser("~"), ".fixapk", "fixapk.keystore")
DEFAULT_ALIAS = "fixapk"
DEFAULT_STOREPASS = "fixapk"


class SignError(Exception):
    pass


@dataclass
class SignResult:
    scheme: str  # "v1" or "v1+v2+v3"
    keystore: str
    verified: bool


def _run(cmd: list[str]) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, capture_output=True, text=True)


def _require(tool: str) -> str:
    path = shutil.which(tool)
    if not path:
        raise SignError(f"required tool not found on PATH: {tool}")
    return path


def ensure_keystore(
    keystore: str = DEFAULT_KEYSTORE,
    alias: str = DEFAULT_ALIAS,
    storepass: str = DEFAULT_STOREPASS,
) -> str:
    """Create a self-signed keystore if it does not exist yet."""
    if os.path.exists(keystore):
        return keystore
    keytool = _require("keytool")
    os.makedirs(os.path.dirname(keystore) or ".", exist_ok=True)
    proc = _run(
        [
            keytool,
            "-genkeypair",
            "-keystore", keystore,
            "-storepass", storepass,
            "-keypass", storepass,
            "-alias", alias,
            "-keyalg", "RSA",
            "-keysize", "2048",
            "-validity", "10000",
            "-dname", "CN=fixapk, OU=fixapk, O=fixapk, C=US",
        ]
    )
    if proc.returncode != 0:
        raise SignError(f"keytool failed:\n{proc.stderr or proc.stdout}")
    return keystore


def find_apksigner() -> str | None:
    """Best-effort discovery of apksigner from PATH or ANDROID_HOME."""
    found = shutil.which("apksigner")
    if found:
        return found
    for env in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        root = os.environ.get(env)
        if not root:
            continue
        bt = os.path.join(root, "build-tools")
        if not os.path.isdir(bt):
            continue
        for ver in sorted(os.listdir(bt), reverse=True):
            for exe in ("apksigner", "apksigner.bat"):
                cand = os.path.join(bt, ver, exe)
                if os.path.isfile(cand):
                    return cand
    return None


def _sign_v1(apk: str, keystore: str, alias: str, storepass: str) -> None:
    jarsigner = _require("jarsigner")
    proc = _run(
        [
            jarsigner,
            "-keystore", keystore,
            "-storepass", storepass,
            "-keypass", storepass,
            "-digestalg", "SHA-256",
            "-sigalg", "SHA256withRSA",
            apk,
            alias,
        ]
    )
    if proc.returncode != 0:
        raise SignError(f"jarsigner failed:\n{proc.stderr or proc.stdout}")


def _verify_v1(apk: str) -> bool:
    jarsigner = shutil.which("jarsigner")
    if not jarsigner:
        return False
    proc = _run([jarsigner, "-verify", apk])
    return proc.returncode == 0


def _sign_apksigner(
    apksigner: str, apk: str, keystore: str, alias: str, storepass: str
) -> None:
    proc = _run(
        [
            apksigner, "sign",
            "--ks", keystore,
            "--ks-key-alias", alias,
            "--ks-pass", f"pass:{storepass}",
            "--key-pass", f"pass:{storepass}",
            apk,
        ]
    )
    if proc.returncode != 0:
        raise SignError(f"apksigner failed:\n{proc.stderr or proc.stdout}")


def _verify_apksigner(apksigner: str, apk: str) -> bool:
    proc = _run([apksigner, "verify", apk])
    return proc.returncode == 0


def sign(
    apk: str,
    *,
    need_v2: bool = False,
    keystore: str = DEFAULT_KEYSTORE,
    alias: str = DEFAULT_ALIAS,
    storepass: str = DEFAULT_STOREPASS,
) -> SignResult:
    """Sign ``apk`` in place, choosing the strongest scheme available."""
    ensure_keystore(keystore, alias, storepass)
    apksigner = find_apksigner()

    if apksigner:
        _sign_apksigner(apksigner, apk, keystore, alias, storepass)
        return SignResult(
            scheme="v1+v2+v3",
            keystore=keystore,
            verified=_verify_apksigner(apksigner, apk),
        )

    if need_v2:
        # We cannot produce a v2 signature without apksigner; sign v1 anyway
        # but let the caller warn the user that a 30+ target won't install.
        _sign_v1(apk, keystore, alias, storepass)
        return SignResult(scheme="v1", keystore=keystore, verified=_verify_v1(apk))

    _sign_v1(apk, keystore, alias, storepass)
    return SignResult(scheme="v1", keystore=keystore, verified=_verify_v1(apk))
