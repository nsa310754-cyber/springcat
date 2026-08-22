package com.apkbuilder.core.pwa

data class PwaIcon(
    val src: String,
    val sizes: String?,
    val type: String?,
    val purpose: String?,
) {
    /** Largest side length declared in `sizes` (e.g. "512x512" -> 512, "any" -> 0). */
    fun maxDimension(): Int =
        (sizes ?: "").split(" ")
            .mapNotNull { token -> token.split("x", "X").firstOrNull()?.toIntOrNull() }
            .maxOrNull() ?: 0

    /** Android's BitmapFactory can't decode SVG — steer icon selection away from it. */
    fun isRaster(): Boolean {
        val t = type?.lowercase()
        if (t != null) return t != "image/svg+xml"
        return !src.substringBefore('?').endsWith(".svg", ignoreCase = true)
    }
}

data class PwaManifest(
    val manifestUrl: String,
    val name: String?,
    val shortName: String?,
    val startUrl: String,
    val display: String?,
    val themeColor: String?,
    val backgroundColor: String?,
    val icons: List<PwaIcon>,
) {
    val displayName: String? get() = name?.takeIf { it.isNotBlank() } ?: shortName?.takeIf { it.isNotBlank() }

    /**
     * Best icon to use as the launcher icon: prefers a raster (PNG/JPEG/WEBP) image — Android's
     * BitmapFactory can't decode SVG — with "any"/unset purpose, largest first.
     */
    fun bestIcon(): PwaIcon? {
        val candidates = icons.filter { it.purpose == null || it.purpose == "any" }.ifEmpty { icons }
        return candidates.filter { it.isRaster() }.maxByOrNull { it.maxDimension() }
            ?: candidates.maxByOrNull { it.maxDimension() }
    }
}
