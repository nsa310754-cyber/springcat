package com.springcat.ide.core.file

import com.springcat.ide.core.lang.Language

/**
 * Minimal in-memory hand-off between the editor and the preview runner, so the
 * (potentially large) source text isn't marshalled through navigation args.
 */
object EditorBridge {
    @Volatile
    var fileName: String = ""

    @Volatile
    var source: String = ""

    val language: Language get() = Language.fromFileName(fileName)

    fun set(name: String, content: String) {
        fileName = name
        source = content
    }
}
