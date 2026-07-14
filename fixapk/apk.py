"""APK repackaging: patch the manifest and strip the old signature.

The heavy lifting of turning "old apk" into "installs on a modern phone" is:

1. bump the manifest ``targetSdkVersion`` (or ``minSdkVersion`` when target
   is implicit) up to at least :data:`INSTALLABLE_TARGET_SDK` so Android 14/15
   stop rejecting it as too old, and
2. throw away the stale ``META-INF`` signature so the app can be re-signed.

Signing itself lives in :mod:`fixapk.signer`.
"""

from __future__ import annotations

import re
import zipfile
from dataclasses import dataclass, field

from . import axml

# Android 14 (API 34) refuses to install apps whose targetSdk < 23.
# Android 15 (API 35) raises that floor to 24.  24 satisfies both and, being
# below 30, keeps the app installable with a v1 (JAR) signature alone.
INSTALLABLE_TARGET_SDK = 24

# targetSdk >= 30 requires an APK Signature Scheme v2+ signature.
V2_REQUIRED_TARGET_SDK = 30

MANIFEST_NAME = "AndroidManifest.xml"

_SIG_FILE_RE = re.compile(r"^META-INF/[^/]+\.(SF|RSA|DSA|EC)$", re.IGNORECASE)
_MANIFEST_MF_RE = re.compile(r"^META-INF/MANIFEST\.MF$", re.IGNORECASE)


class ApkError(Exception):
    pass


@dataclass
class RepackResult:
    old_min_sdk: int | None
    old_target_sdk: int | None
    new_target_sdk: int
    changes: dict = field(default_factory=dict)
    stripped_signatures: list = field(default_factory=list)

    @property
    def needs_v2(self) -> bool:
        return self.new_target_sdk >= V2_REQUIRED_TARGET_SDK


def _is_signature_entry(name: str) -> bool:
    return bool(_SIG_FILE_RE.match(name) or _MANIFEST_MF_RE.match(name))


def _read_manifest(zf: zipfile.ZipFile) -> bytes:
    try:
        return zf.read(MANIFEST_NAME)
    except KeyError:
        raise ApkError(f"{MANIFEST_NAME} not found — is this really an APK?")


def _plan_patch(min_sdk: int | None, target_sdk: int | None, floor: int):
    """Decide which attribute to bump so the effective target reaches ``floor``.

    Returns ``(patch_kwargs, new_effective_target)``.
    """
    if target_sdk is not None:
        new_target = max(target_sdk, floor)
        if new_target != target_sdk:
            return {"target_sdk": new_target}, new_target
        return {}, target_sdk

    # No explicit targetSdkVersion: Android treats target as == minSdkVersion.
    if min_sdk is not None:
        effective = min_sdk
        new_target = max(min_sdk, floor)
        if new_target != min_sdk:
            return {"min_sdk": new_target}, new_target
        return {}, effective

    raise ApkError(
        "manifest has no minSdkVersion/targetSdkVersion to bump; this APK "
        "cannot be patched in place (needs a full aapt/apktool rebuild)."
    )


def repackage(
    input_path: str,
    output_path: str,
    *,
    target_floor: int = INSTALLABLE_TARGET_SDK,
) -> RepackResult:
    """Produce an *unsigned* APK at ``output_path`` with a patched manifest.

    Raises :class:`ApkError` on anything we cannot safely handle.
    """
    with zipfile.ZipFile(input_path, "r") as zin:
        manifest = _read_manifest(zin)

        try:
            versions = axml.read_sdk_versions(manifest)
        except axml.AxmlError as exc:
            raise ApkError(f"could not parse manifest: {exc}")

        min_sdk = versions["minSdkVersion"]
        target_sdk = versions["targetSdkVersion"]

        patch_kwargs, new_target = _plan_patch(min_sdk, target_sdk, target_floor)
        if patch_kwargs:
            manifest, changes = axml.patch_sdk_versions(manifest, **patch_kwargs)
        else:
            changes = {}

        stripped = []
        with zipfile.ZipFile(output_path, "w") as zout:
            for info in zin.infolist():
                name = info.filename
                if _is_signature_entry(name):
                    stripped.append(name)
                    continue
                data = manifest if name == MANIFEST_NAME else zin.read(name)
                # Preserve the original per-entry compression so resources.arsc
                # and friends keep whatever storage the app relied on.
                new_info = zipfile.ZipInfo(name, date_time=info.date_time)
                new_info.compress_type = info.compress_type
                new_info.external_attr = info.external_attr
                new_info.internal_attr = info.internal_attr
                new_info.create_system = info.create_system
                zout.writestr(new_info, data)

    return RepackResult(
        old_min_sdk=min_sdk,
        old_target_sdk=target_sdk,
        new_target_sdk=new_target,
        changes=changes,
        stripped_signatures=stripped,
    )
