package com.apkbuilder.core.pwa

import com.apkbuilder.core.json.MiniJson
import com.apkbuilder.core.json.arr
import com.apkbuilder.core.json.get
import com.apkbuilder.core.json.str
import java.net.URI

object PwaManifestParser {

    /** [manifestUrl] is the absolute URL the manifest was fetched from — the base for resolving relative URLs in it. */
    fun parse(manifestJsonText: String, manifestUrl: String): PwaManifest {
        val root = MiniJson.parse(manifestJsonText)

        val startUrlRaw = root["start_url"].str() ?: "."
        val icons = root["icons"].arr().mapNotNull { iconValue ->
            val src = iconValue["src"].str() ?: return@mapNotNull null
            PwaIcon(
                src = resolve(manifestUrl, src),
                sizes = iconValue["sizes"].str(),
                type = iconValue["type"].str(),
                purpose = iconValue["purpose"].str(),
            )
        }

        return PwaManifest(
            manifestUrl = manifestUrl,
            name = root["name"].str(),
            shortName = root["short_name"].str(),
            startUrl = resolve(manifestUrl, startUrlRaw),
            display = root["display"].str(),
            themeColor = root["theme_color"].str(),
            backgroundColor = root["background_color"].str(),
            icons = icons,
        )
    }

    private fun resolve(baseUrl: String, ref: String): String =
        runCatching { URI(baseUrl).resolve(ref).toString() }.getOrDefault(ref)
}
