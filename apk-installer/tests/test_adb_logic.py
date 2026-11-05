"""adb まわりのロジックのテスト (tkinter 不要・標準ライブラリのみ)。

実行: python -m unittest discover -s tests -v   (apk-installer フォルダで)
"""
from __future__ import annotations

import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import apk_installer as ai  # noqa: E402


class ParseDevicesTest(unittest.TestCase):
    def test_parses_ready_and_unauthorized(self):
        out = (
            "* daemon not running; starting now at tcp:5037\n"
            "* daemon started successfully\n"
            "List of devices attached\n"
            "R58M123ABC           device usb:1-1 product:gta model:SM_S911B device:gta transport_id:1\n"
            "emulator-5554        unauthorized transport_id:2\n"
            "\n"
        )
        devices = ai.parse_devices(out)
        self.assertEqual(len(devices), 2)
        self.assertEqual(devices[0].serial, "R58M123ABC")
        self.assertEqual(devices[0].model, "SM S911B")
        self.assertTrue(devices[0].ready)
        self.assertEqual(devices[1].state, "unauthorized")
        self.assertFalse(devices[1].ready)
        self.assertIn("USB デバッグ", devices[1].label)

    def test_empty_list(self):
        self.assertEqual(ai.parse_devices("List of devices attached\n\n"), [])


class BuildInstallCmdTest(unittest.TestCase):
    def test_default_options(self):
        cmd = ai.build_install_cmd("adb", "SER1", Path("a.apk"), ai.InstallOptions())
        self.assertEqual(cmd, ["adb", "-s", "SER1", "install", "-r", "a.apk"])

    def test_all_options(self):
        opts = ai.InstallOptions(replace=True, downgrade=True, grant_all=True)
        cmd = ai.build_install_cmd("adb", "SER1", Path("a.apk"), opts)
        self.assertEqual(cmd, ["adb", "-s", "SER1", "install", "-r", "-d", "-g", "a.apk"])

    def test_no_serial_and_no_replace(self):
        opts = ai.InstallOptions(replace=False)
        cmd = ai.build_install_cmd("adb", "", Path("a.apk"), opts)
        self.assertEqual(cmd, ["adb", "install", "a.apk"])


class InterpretResultTest(unittest.TestCase):
    def test_success(self):
        ok, msg = ai.interpret_install_result(0, "Performing Streamed Install\nSuccess\n")
        self.assertTrue(ok)
        self.assertEqual(msg, "成功")

    def test_failure_with_code(self):
        out = "Performing Streamed Install\nFailure [INSTALL_FAILED_VERSION_DOWNGRADE: Downgrade detected]\n"
        ok, msg = ai.interpret_install_result(1, out)
        self.assertFalse(ok)
        self.assertIn("INSTALL_FAILED_VERSION_DOWNGRADE", msg)
        self.assertIn("ダウングレード", ai.failure_hint(out))

    def test_success_text_but_nonzero_exit_is_failure(self):
        ok, _ = ai.interpret_install_result(1, "Success\n")
        self.assertFalse(ok)

    def test_adb_error_line(self):
        ok, msg = ai.interpret_install_result(1, "adb: error: failed to stat remote object\n")
        self.assertFalse(ok)
        self.assertTrue(msg.startswith("adb:"))

    def test_unknown_failure_has_no_hint(self):
        self.assertIsNone(ai.failure_hint("Failure [SOMETHING_NEW]"))


class LooksLikeApkTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self._tmp.name)

    def tearDown(self):
        self._tmp.cleanup()

    def _zip(self, name: str, members: list[str]) -> Path:
        path = self.dir / name
        with zipfile.ZipFile(path, "w") as z:
            for m in members:
                z.writestr(m, b"x")
        return path

    def test_valid_apk(self):
        self.assertTrue(ai.looks_like_apk(self._zip("ok.apk", ["AndroidManifest.xml", "classes.dex"])))

    def test_zip_without_manifest(self):
        self.assertFalse(ai.looks_like_apk(self._zip("nomani.apk", ["readme.txt"])))

    def test_wrong_extension(self):
        self.assertFalse(ai.looks_like_apk(self._zip("ok.zip", ["AndroidManifest.xml"])))

    def test_not_a_zip(self):
        path = self.dir / "broken.apk"
        path.write_bytes(b"not a zip at all")
        self.assertFalse(ai.looks_like_apk(path))

    def test_missing_file(self):
        self.assertFalse(ai.looks_like_apk(self.dir / "missing.apk"))


class FindAdbTest(unittest.TestCase):
    def test_finds_platform_tools_next_to_base(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            adb = base / "platform-tools" / "adb.exe"
            adb.parent.mkdir()
            adb.write_bytes(b"")
            self.assertEqual(ai.find_adb(base), str(adb))


class SafeExtractTest(unittest.TestCase):
    def test_rejects_path_traversal(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            zip_path = tmp_path / "evil.zip"
            with zipfile.ZipFile(zip_path, "w") as z:
                z.writestr("../escape.txt", b"x")
            dest = tmp_path / "dest"
            dest.mkdir()
            with self.assertRaises(RuntimeError):
                ai.extract_zip_safely(zip_path, dest)
            self.assertFalse((tmp_path / "escape.txt").exists())

    def test_extracts_normal_archive(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            zip_path = tmp_path / "ok.zip"
            with zipfile.ZipFile(zip_path, "w") as z:
                z.writestr("platform-tools/adb.exe", b"x")
            dest = tmp_path / "dest"
            dest.mkdir()
            ai.extract_zip_safely(zip_path, dest)
            self.assertTrue((dest / "platform-tools" / "adb.exe").is_file())


if __name__ == "__main__":
    unittest.main()
