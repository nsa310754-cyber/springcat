package com.springcat.iso

/**
 * A single entry (file or directory) inside an ISO 9660 image.
 *
 * @param name          Display name of the entry.
 * @param isDirectory   True if this entry is a directory.
 * @param size          Size in bytes of the file data (0 for directories).
 * @param extentLba     Logical Block Address (2048-byte sector) where this
 *                      entry's data / directory record starts.
 */
data class IsoEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val extentLba: Long
) {
    /** Human readable size such as "1.4 MB". */
    fun readableSize(): String {
        if (isDirectory) return ""
        if (size < 1024) return "$size B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = size.toDouble() / 1024.0
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.size - 1) {
            value /= 1024.0
            unitIndex++
        }
        return String.format("%.1f %s", value, units[unitIndex])
    }
}
