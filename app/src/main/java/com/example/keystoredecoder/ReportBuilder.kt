package com.example.keystoredecoder

/** Turns a [KeystoreDecoder.KeystoreInfo] into a keytool-style text report. */
object ReportBuilder {

    fun build(fileName: String?, info: KeystoreDecoder.KeystoreInfo): String {
        val sb = StringBuilder()
        sb.appendLine("✅ Keystore opened successfully")
        sb.appendLine("──────────────────────────────")
        fileName?.let { sb.appendLine("File:      $it") }
        sb.appendLine("Type:      ${info.detectedType}")
        sb.appendLine("Provider:  ${info.provider}")
        sb.appendLine("Entries:   ${info.entryCount}")
        info.note?.let {
            sb.appendLine()
            sb.appendLine(it)
        }
        sb.appendLine()

        if (info.entries.isEmpty()) {
            sb.appendLine("(This keystore has no entries.)")
            return sb.toString()
        }

        info.entries.forEachIndexed { index, entry ->
            sb.appendLine("═══ Entry ${index + 1} of ${info.entryCount} ═══")
            sb.appendLine("Alias:         ${entry.alias}")
            sb.appendLine("Entry type:    ${entry.entryType}")
            entry.creationDate?.let { sb.appendLine("Created:       $it") }
            sb.appendLine("Chain length:  ${entry.certificates.size}")
            sb.appendLine()

            entry.certificates.forEachIndexed { ci, cert ->
                if (entry.certificates.size > 1) {
                    sb.appendLine("  ── Certificate ${ci + 1} ──")
                }
                sb.appendLine("  Subject:     ${cert.subject}")
                sb.appendLine("  Issuer:      ${cert.issuer}")
                sb.appendLine("  Serial:      ${cert.serialNumber}")
                sb.appendLine("  Valid from:  ${cert.validFrom}")
                sb.appendLine("  Valid until: ${cert.validUntil}${if (cert.expired) "  ⚠️ EXPIRED / NOT YET VALID" else ""}")
                sb.appendLine("  Version:     v${cert.version}")
                sb.appendLine("  Sig alg:     ${cert.signatureAlgorithm}")
                sb.appendLine("  Public key:  ${cert.publicKeyInfo}")
                sb.appendLine("  Fingerprints:")
                sb.appendLine("    SHA-256:   ${cert.sha256}")
                sb.appendLine("    SHA-1:     ${cert.sha1}")
                sb.appendLine("    MD5:       ${cert.md5}")
                sb.appendLine()
            }
        }
        sb.appendLine("──────────────────────────────")
        sb.appendLine("Tip: SHA-1 / SHA-256 of the signing cert are the")
        sb.appendLine("fingerprints Firebase & Google APIs ask for.")
        return sb.toString()
    }
}
