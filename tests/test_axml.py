"""Unit tests for the AXML patcher.

Because we have no aapt in the environment, the tests build a tiny but
format-accurate binary AndroidManifest.xml by hand (string pool + resource
map + a single ``<uses-sdk>`` element) and then exercise read / patch on it.
"""

import os
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from fixapk import axml


def _build_string_pool(strings):
    count = len(strings)
    offsets = b""
    data = b""
    for s in strings:
        offsets += struct.pack("<I", len(data))
        encoded = s.encode("utf-16-le")
        data += struct.pack("<H", len(s)) + encoded + b"\x00\x00"
    # pad string data to 4 bytes
    while len(data) % 4:
        data += b"\x00"
    strings_start = 28 + len(offsets)
    size = strings_start + len(data)
    header = struct.pack(
        "<HHIIIIII",
        0x0001,  # type
        28,  # header size
        size,  # chunk size
        count,  # string count
        0,  # style count
        0,  # flags (UTF-16)
        strings_start,  # strings start
        0,  # styles start
    )
    return header + offsets + data


def _build_resource_map(ids):
    size = 8 + 4 * len(ids)
    header = struct.pack("<HHI", 0x0180, 8, size)
    return header + b"".join(struct.pack("<I", i) for i in ids)


def _build_start_element(name_index, attrs):
    """attrs: list of (name_index, data_type, value)."""
    attr_bytes = b""
    for name_index_a, data_type, value in attrs:
        attr_bytes += struct.pack(
            "<IIIHBBI",
            0xFFFFFFFF,  # ns
            name_index_a,  # name
            0xFFFFFFFF,  # rawValue
            8,  # typedValue size
            0,  # res0
            data_type,  # typedValue dataType
            value,  # typedValue data
        )
    body = struct.pack(
        "<IIIIHHHHHH",
        0xFFFFFFFF,  # lineNumber
        0xFFFFFFFF,  # comment
        0xFFFFFFFF,  # ns
        name_index,  # name
        20,  # attributeStart
        20,  # attributeSize
        len(attrs),  # attributeCount
        0,  # idIndex
        0,  # classIndex
        0,  # styleIndex
    )
    size = 8 + len(body) + len(attr_bytes)
    header = struct.pack("<HHI", 0x0102, 16, size)
    return header + body + attr_bytes


def build_manifest(min_sdk=None, target_sdk=None):
    # attribute-name strings must be the leading strings so the resource map
    # lines up with them, exactly like a real compiled manifest.
    strings = ["minSdkVersion", "targetSdkVersion", "uses-sdk"]
    resmap = [axml.ATTR_MIN_SDK, axml.ATTR_TARGET_SDK]

    attrs = []
    if min_sdk is not None:
        attrs.append((0, axml.TYPE_INT_DEC, min_sdk))
    if target_sdk is not None:
        attrs.append((1, axml.TYPE_INT_DEC, target_sdk))

    pool = _build_string_pool(strings)
    rmap = _build_resource_map(resmap)
    elem = _build_start_element(2, attrs)

    body = pool + rmap + elem
    total = 8 + len(body)
    header = struct.pack("<HHI", 0x0003, 8, total)
    return header + body


def test_read_versions():
    data = build_manifest(min_sdk=9, target_sdk=14)
    v = axml.read_sdk_versions(data)
    assert v == {"minSdkVersion": 9, "targetSdkVersion": 14}, v


def test_patch_target_in_place():
    data = build_manifest(min_sdk=9, target_sdk=14)
    out, changes = axml.patch_sdk_versions(data, target_sdk=24)
    assert len(out) == len(data), "in-place patch must not change size"
    assert changes["targetSdkVersion"] == (14, 24), changes
    v = axml.read_sdk_versions(out)
    assert v == {"minSdkVersion": 9, "targetSdkVersion": 24}, v


def test_patch_min_only_manifest():
    # old apps often declare only minSdkVersion; target defaults to it.
    data = build_manifest(min_sdk=8, target_sdk=None)
    v = axml.read_sdk_versions(data)
    assert v == {"minSdkVersion": 8, "targetSdkVersion": None}, v
    out, changes = axml.patch_sdk_versions(data, min_sdk=24)
    assert changes["minSdkVersion"] == (8, 24), changes
    assert axml.read_sdk_versions(out)["minSdkVersion"] == 24


def test_no_change_when_value_matches():
    # the low-level setter only reports a change when the value actually
    # differs; "don't lower" policy lives in the orchestrator.
    data = build_manifest(min_sdk=24, target_sdk=24)
    out, changes = axml.patch_sdk_versions(data, target_sdk=24)
    assert changes["targetSdkVersion"] is None
    assert out == data


if __name__ == "__main__":
    for name, fn in sorted(globals().items()):
        if name.startswith("test_") and callable(fn):
            fn()
            print(f"ok  {name}")
    print("all axml tests passed")
