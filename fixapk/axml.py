"""Minimal Android binary XML (AXML) reader / in-place patcher.

This module intentionally does **no** re-serialisation of the binary XML.
It only rewrites the 4-byte integer *value* of the ``minSdkVersion`` /
``targetSdkVersion`` attributes of the ``<uses-sdk>`` element, editing the
bytes in place.  Because the edit keeps the exact same size, no chunk
offsets, string pool, or file-size fields have to change, which makes the
operation safe and dependency free (no aapt / apktool required).

Format reference: AOSP ``frameworks/base`` ``ResourceTypes.h``.
"""

from __future__ import annotations

import struct
from dataclasses import dataclass

# Chunk types
RES_STRING_POOL_TYPE = 0x0001
RES_XML_TYPE = 0x0003
RES_XML_START_ELEMENT_TYPE = 0x0102
RES_XML_RESOURCE_MAP_TYPE = 0x0180

# String pool flags
UTF8_FLAG = 0x0100

# Res_value data types
TYPE_INT_DEC = 0x10

# Android framework attribute resource ids
ATTR_MIN_SDK = 0x0101020C
ATTR_TARGET_SDK = 0x01010270

NO_ENTRY = 0xFFFFFFFF


class AxmlError(Exception):
    """Raised when the byte stream is not a manifest we can safely patch."""


def _u16(buf: bytes, off: int) -> int:
    return struct.unpack_from("<H", buf, off)[0]


def _u32(buf: bytes, off: int) -> int:
    return struct.unpack_from("<I", buf, off)[0]


def _decode_len8(buf: bytes, off: int) -> tuple[int, int]:
    b = buf[off]
    off += 1
    if b & 0x80:
        b = ((b & 0x7F) << 8) | buf[off]
        off += 1
    return b, off


def _decode_len16(buf: bytes, off: int) -> tuple[int, int]:
    v = _u16(buf, off)
    off += 2
    if v & 0x8000:
        v = ((v & 0x7FFF) << 16) | _u16(buf, off)
        off += 2
    return v, off


class StringPool:
    """Lazily decodes strings out of a ResStringPool chunk."""

    def __init__(self, buf: bytes, chunk_off: int):
        typ = _u16(buf, chunk_off)
        if typ != RES_STRING_POOL_TYPE:
            raise AxmlError("expected string pool chunk")
        self._buf = buf
        self._base = chunk_off
        self.count = _u32(buf, chunk_off + 8)
        flags = _u32(buf, chunk_off + 16)
        self.utf8 = bool(flags & UTF8_FLAG)
        self.strings_start = _u32(buf, chunk_off + 20)
        self._offsets_at = chunk_off + 28
        self._cache: dict[int, str] = {}

    def get(self, index: int) -> str:
        if index < 0 or index >= self.count or index == 0xFFFFFFFF:
            return ""
        if index in self._cache:
            return self._cache[index]
        buf = self._buf
        rel = _u32(buf, self._offsets_at + index * 4)
        off = self._base + self.strings_start + rel
        if self.utf8:
            _, off = _decode_len8(buf, off)  # char count
            byte_len, off = _decode_len8(buf, off)
            s = buf[off : off + byte_len].decode("utf-8", "replace")
        else:
            char_len, off = _decode_len16(buf, off)
            s = buf[off : off + char_len * 2].decode("utf-16-le", "replace")
        self._cache[index] = s
        return s


@dataclass
class _Attribute:
    res_id: int
    name: str
    data_type: int
    value: int
    data_off: int  # absolute offset of the 4-byte typedValue.data
    type_off: int  # absolute offset of the 1-byte typedValue.dataType
    raw_off: int  # absolute offset of the 4-byte rawValue reference


def _iter_chunks(buf: bytes):
    """Yield ``(chunk_type, offset)`` for every top level chunk after the
    outer RES_XML header."""
    if len(buf) < 8 or _u16(buf, 0) != RES_XML_TYPE:
        raise AxmlError("not a binary AndroidManifest.xml (bad magic)")
    total = _u32(buf, 4)
    total = min(total, len(buf))
    header_size = _u16(buf, 2)
    off = header_size
    while off + 8 <= total:
        ctype = _u16(buf, off)
        csize = _u32(buf, off + 4)
        if csize < 8:
            break
        yield ctype, off
        off += csize


def _find_string_pool_and_resmap(buf: bytes):
    pool = None
    resmap_off = None
    resmap_count = 0
    for ctype, off in _iter_chunks(buf):
        if ctype == RES_STRING_POOL_TYPE and pool is None:
            pool = StringPool(buf, off)
        elif ctype == RES_XML_RESOURCE_MAP_TYPE and resmap_off is None:
            header_size = _u16(buf, off + 2)
            csize = _u32(buf, off + 4)
            resmap_off = off + header_size
            resmap_count = (csize - header_size) // 4
    if pool is None:
        raise AxmlError("no string pool found")
    return pool, resmap_off, resmap_count


def _res_id_for_name(buf, resmap_off, resmap_count, name_index) -> int:
    if resmap_off is None or name_index >= resmap_count:
        return 0
    return _u32(buf, resmap_off + name_index * 4)


def _uses_sdk_attributes(buf: bytes) -> list[_Attribute]:
    """Return the parsed attributes of the first ``<uses-sdk>`` element."""
    pool, resmap_off, resmap_count = _find_string_pool_and_resmap(buf)
    for ctype, off in _iter_chunks(buf):
        if ctype != RES_XML_START_ELEMENT_TYPE:
            continue
        name_idx = _u32(buf, off + 20)
        if pool.get(name_idx) != "uses-sdk":
            continue
        attr_start = _u16(buf, off + 24)
        attr_size = _u16(buf, off + 26)
        attr_count = _u16(buf, off + 28)
        first = off + 16 + attr_start
        attrs: list[_Attribute] = []
        for i in range(attr_count):
            a = first + i * attr_size
            name_index = _u32(buf, a + 4)
            res_id = _res_id_for_name(buf, resmap_off, resmap_count, name_index)
            attrs.append(
                _Attribute(
                    res_id=res_id,
                    name=pool.get(name_index),
                    data_type=buf[a + 15],
                    value=_u32(buf, a + 16),
                    data_off=a + 16,
                    type_off=a + 15,
                    raw_off=a + 8,
                )
            )
        return attrs
    raise AxmlError("no <uses-sdk> element found")


def _match(attr: _Attribute, res_id: int, name: str) -> bool:
    return attr.res_id == res_id or attr.name == name


def read_sdk_versions(data: bytes) -> dict:
    """Return ``{'minSdkVersion': int|None, 'targetSdkVersion': int|None}``.

    Missing attributes are reported as ``None``.  Raises :class:`AxmlError`
    when there is no ``<uses-sdk>`` element at all.
    """
    attrs = _uses_sdk_attributes(data)
    out = {"minSdkVersion": None, "targetSdkVersion": None}
    for a in attrs:
        if _match(a, ATTR_MIN_SDK, "minSdkVersion"):
            out["minSdkVersion"] = a.value
        elif _match(a, ATTR_TARGET_SDK, "targetSdkVersion"):
            out["targetSdkVersion"] = a.value
    return out


def patch_sdk_versions(
    data: bytes, *, min_sdk: int | None = None, target_sdk: int | None = None
) -> tuple[bytes, dict]:
    """Rewrite the ``<uses-sdk>`` integer attributes in place.

    Only attributes that already exist are edited.  Returns the new bytes and
    a dict describing what happened, e.g.::

        {'targetSdkVersion': (14, 24), 'minSdkVersion': None}

    where a tuple is ``(old, new)`` and ``None`` means "not present / not
    changed".  If no editable attribute exists at all, callers should treat
    that as "cannot patch in place".
    """
    buf = bytearray(data)
    attrs = _uses_sdk_attributes(buf)
    changes = {"minSdkVersion": None, "targetSdkVersion": None}

    def apply(attr: _Attribute, new_val: int):
        struct.pack_into("<I", buf, attr.data_off, new_val)
        buf[attr.type_off] = TYPE_INT_DEC
        struct.pack_into("<I", buf, attr.raw_off, NO_ENTRY)

    for a in attrs:
        if target_sdk is not None and _match(a, ATTR_TARGET_SDK, "targetSdkVersion"):
            if a.value != target_sdk:
                apply(a, target_sdk)
                changes["targetSdkVersion"] = (a.value, target_sdk)
        elif min_sdk is not None and _match(a, ATTR_MIN_SDK, "minSdkVersion"):
            if a.value != min_sdk:
                apply(a, min_sdk)
                changes["minSdkVersion"] = (a.value, min_sdk)

    return bytes(buf), changes
