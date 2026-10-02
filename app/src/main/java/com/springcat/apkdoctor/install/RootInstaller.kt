package com.springcat.apkdoctor.install

import java.io.File

/** Result of running one shell command. */
data class ExecResult(val code: Int, val stdout: String, val stderr: String) {
    val ok: Boolean get() = code == 0
    val combined: String get() = (stdout + "\n" + stderr).trim()
}

/** Runs a command line, optionally feeding a file to its stdin. Injectable for tests. */
interface CommandRunner {
    fun exec(argv: List<String>, stdinFile: File? = null): ExecResult
}

/** Runs commands through the `su` binary on a rooted device. */
class SuCommandRunner : CommandRunner {
    override fun exec(argv: List<String>, stdinFile: File?): ExecResult {
        val process = ProcessBuilder(argv)
            .redirectErrorStream(false)
            .apply { stdinFile?.let { redirectInput(it) } }
            .start()
        // No stdinFile means the command takes no input; close its stream so it
        // does not block waiting for one.
        if (stdinFile == null) process.outputStream.close()
        val out = process.inputStream.readBytes().toString(Charsets.UTF_8)
        val err = process.errorStream.readBytes().toString(Charsets.UTF_8)
        val code = process.waitFor()
        return ExecResult(code, out, err)
    }
}

/**
 * Installs an APK (or a base plus its splits) with root privileges by driving
 * `pm install-create` / `install-write` / `install-commit` through `su`.
 *
 * This path keeps the APK's original signature — nothing is re-signed — and can
 * do what the normal installer refuses: silent install without the
 * unknown-sources prompt, downgrades (`-d`), test-only APKs (`-t`), and
 * replacing an app signed with a different key.
 */
class RootInstaller(private val runner: CommandRunner = SuCommandRunner()) {

    data class Options(
        val reinstall: Boolean = true,
        val allowDowngrade: Boolean = true,
        val allowTest: Boolean = true,
        val grantPermissions: Boolean = true,
    )

    /** True when a working `su` that yields uid 0 is present. */
    fun isAvailable(): Boolean = runCatching {
        val result = runner.exec(listOf("su", "-c", "id -u"))
        result.ok && result.stdout.trim().startsWith("0")
    }.getOrDefault(false)

    /** Builds the `pm install-create` flags for [options]; visible for testing. */
    fun createCommand(options: Options): String = buildString {
        append("pm install-create")
        if (options.reinstall) append(" -r")
        if (options.allowDowngrade) append(" -d")
        if (options.allowTest) append(" -t")
        if (options.grantPermissions) append(" -g")
    }

    /**
     * Installs [files] (base first) as one session. Returns the user-facing
     * result message, or throws [RootInstallException] on failure.
     */
    fun install(files: List<File>, options: Options = Options()): String {
        require(files.isNotEmpty()) { "no APKs to install" }

        val created = runner.exec(listOf("su", "-c", createCommand(options)))
        if (!created.ok) throw RootInstallException("セッションを作成できませんでした: ${created.combined}")
        val sessionId = SESSION_ID.find(created.combined)?.groupValues?.get(1)
            ?: throw RootInstallException("セッションIDを取得できませんでした: ${created.combined}")

        try {
            files.forEachIndexed { index, file ->
                // The stream name must be unique within the session; the device
                // derives the split identity from the APK's own manifest.
                val name = "${index}_${file.name.replace(Regex("[^A-Za-z0-9._-]"), "_")}"
                val cmd = "pm install-write -S ${file.length()} $sessionId $name -"
                val written = runner.exec(listOf("su", "-c", cmd), stdinFile = file)
                if (!written.ok) {
                    throw RootInstallException("${file.name} の書き込みに失敗しました: ${written.combined}")
                }
            }

            val committed = runner.exec(listOf("su", "-c", "pm install-commit $sessionId"))
            if (!committed.ok || !committed.combined.contains("Success", ignoreCase = true)) {
                throw RootInstallException(committed.combined.ifBlank { "インストールに失敗しました" })
            }
            return "root でインストールしました"
        } catch (e: RootInstallException) {
            // Leave no dangling session behind on failure.
            runCatching { runner.exec(listOf("su", "-c", "pm install-abandon $sessionId")) }
            throw e
        }
    }

    private companion object {
        val SESSION_ID = Regex("""\[(\d+)]""")
    }
}

class RootInstallException(message: String) : Exception(message)
