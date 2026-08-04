#!/usr/bin/env python3
"""Reference decoder for the SpringCat .sce (expanded) format v2.

Reproduces the app's key derivation and AES-CTR decryption to verify the
SCOK tag and recover the original filename + contents.
"""
import struct, sys, hashlib
from Crypto.Cipher import AES
from Crypto.Util import Counter

MAGIC = b"SPCXPND2"
TAG = b"SCOK"
DEFAULT_KEY = "SpringCat/expanded/v2/default-obfuscation"
ITERATIONS = 100_000
DKLEN = 32  # AES-256


def derive_key(password: str, salt: bytes) -> bytes:
    # Java PBKDF2WithHmacSHA1 over the password chars. For ASCII/UTF-8 text
    # this matches password.encode("utf-8").
    return hashlib.pbkdf2_hmac("sha1", password.encode("utf-8"), salt, ITERATIONS, DKLEN)


def decrypt_sce(path: str, user_password: str | None):
    with open(path, "rb") as f:
        blob = f.read()

    assert blob[:8] == MAGIC, "not a SPCXPND2 file"
    version = blob[8]
    flags = blob[9]
    needs_pw = bool(flags & 1)
    salt = blob[10:26]
    iv = blob[26:42]
    (enc_len,) = struct.unpack(">q", blob[42:50])   # big-endian signed long
    payload = blob[50:50 + enc_len]

    pw = user_password if needs_pw else DEFAULT_KEY
    if needs_pw and not user_password:
        raise ValueError("password required")

    key = derive_key(pw, salt)
    # AES-CTR: the 16-byte IV is the initial 128-bit big-endian counter,
    # exactly like Java's AES/CTR/NoPadding.
    ctr = Counter.new(128, initial_value=int.from_bytes(iv, "big"))
    plain = AES.new(key, AES.MODE_CTR, counter=ctr).decrypt(payload)

    tag = plain[:4]
    if tag != TAG:
        raise ValueError("SCOK tag mismatch -> wrong password / corrupt")

    (name_len,) = struct.unpack(">H", plain[4:6])   # unsigned short
    name = plain[6:6 + name_len].decode("utf-8")
    off = 6 + name_len
    (orig_size,) = struct.unpack(">q", plain[off:off + 8])
    off += 8
    data = plain[off:off + orig_size]
    return {
        "version": version, "encrypted": needs_pw,
        "name": name, "orig_size": orig_size, "data": data,
    }


if __name__ == "__main__":
    path = sys.argv[1]
    pw = sys.argv[2] if len(sys.argv) > 2 else None
    r = decrypt_sce(path, pw)
    print(f"  tag=SCOK OK | name={r['name']!r} | size={r['orig_size']} | encrypted={r['encrypted']}")
    print(f"  content: {r['data'].decode('utf-8')!r}")
