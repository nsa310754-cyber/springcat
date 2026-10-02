package com.springcat.apkdoctor.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Exercises the root install sequence against a fake command runner, so the
 * `pm install-create / install-write / install-commit` flow is verified without
 * a rooted device.
 */
class RootInstallerTest {

    private class Call(val argv: List<String>, val stdinFile: File?)

    /** Records calls and replies with scripted results keyed by a command substring. */
    private class FakeRunner(private val replies: List<Pair<String, ExecResult>>) : CommandRunner {
        val calls = mutableListOf<Call>()
        override fun exec(argv: List<String>, stdinFile: File?): ExecResult {
            calls.add(Call(argv, stdinFile))
            val cmd = argv.joinToString(" ")
            return replies.firstOrNull { cmd.contains(it.first) }?.second
                ?: ExecResult(0, "", "")
        }
    }

    private fun ok(out: String) = ExecResult(0, out, "")
    private fun fail(err: String) = ExecResult(1, "", err)

    @Test
    fun `install drives create, write per file, then commit`() {
        val runner = FakeRunner(
            listOf(
                "install-create" to ok("Success: created install session [42]"),
                "install-commit" to ok("Success"),
            ),
        )
        val base = File.createTempFile("base", ".apk").apply { writeBytes(ByteArray(100)) }
        val split = File.createTempFile("split", ".apk").apply { writeBytes(ByteArray(50)) }

        val message = RootInstaller(runner).install(listOf(base, split))
        assertTrue(message.contains("root"))

        val cmds = runner.calls.map { it.argv.joinToString(" ") }
        assertTrue("session created first", cmds[0].contains("pm install-create"))
        assertTrue("reinstall + downgrade + test + grant flags", cmds[0].contains("-r") && cmds[0].contains("-d") && cmds[0].contains("-t") && cmds[0].contains("-g"))

        val writes = runner.calls.filter { it.argv.any { a -> a.contains("install-write") } }
        assertEquals("one write per file", 2, writes.size)
        assertTrue("base written into session 42 with its size", writes[0].argv.last().contains("install-write -S 100 42"))
        assertEquals("base bytes streamed from the file", base, writes[0].stdinFile)
        assertEquals(split, writes[1].stdinFile)

        assertTrue("committed last", cmds.last().contains("pm install-commit 42"))
    }

    @Test
    fun `a failed write abandons the session and reports the error`() {
        val runner = FakeRunner(
            listOf(
                "install-create" to ok("Success: created install session [7]"),
                "install-write" to fail("insufficient storage"),
            ),
        )
        val file = File.createTempFile("part", ".apk").apply { writeBytes(ByteArray(10)) }

        val error = assertThrows(RootInstallException::class.java) {
            RootInstaller(runner).install(listOf(file))
        }
        assertTrue(error.message!!.contains("insufficient storage"))
        assertTrue("the half-open session is abandoned", runner.calls.any { it.argv.joinToString(" ").contains("install-abandon 7") })
    }

    @Test
    fun `isAvailable reflects what su reports`() {
        assertTrue(RootInstaller(FakeRunner(listOf("id -u" to ok("0")))).isAvailable())
        assertFalse(RootInstaller(FakeRunner(listOf("id -u" to ok("2000")))).isAvailable())
        assertFalse(RootInstaller(FakeRunner(listOf("id -u" to fail("su: not found")))).isAvailable())
    }

    @Test
    fun `create flags follow the options`() {
        val installer = RootInstaller(FakeRunner(emptyList()))
        assertEquals("pm install-create -r", installer.createCommand(RootInstaller.Options(allowDowngrade = false, allowTest = false, grantPermissions = false)))
        assertTrue(installer.createCommand(RootInstaller.Options()).contains("-d"))
    }
}
