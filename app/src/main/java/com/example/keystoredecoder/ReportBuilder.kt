package com.example.keystoredecoder

/** Turns a [KeystoreDecoder.KeystoreInfo] into a keytool-style text report. */
object ReportBuilder {

    /** A single rendered line in the result list. */
    sealed class Row {
        /** Section title. */
        data class Header(val text: String) : Row()
        /** A labelled value the user can copy individually. */
        data class Field(val label: String, val value: String) : Row()
        /** Free text (notes, tips) — not individually copyable. */
        data class Plain(val text: String) : Row()
    }

    /** Builds the structured, per-field-copyable representation. */
    fun items(fileName: String?, info: KeystoreDecoder.KeystoreInfo): List<Row> {
        val rows = mutableListOf<Row>()
        rows.add(Row.Header("Keystore"))
        fileName?.let { rows.add(Row.Field("File", it)) }
        rows.add(Row.Field("Type", info.detectedType))
        rows.add(Row.Field("Provider", info.provider))
        rows.add(Row.Field("Entries", info.entryCount.toString()))
        info.note?.let { rows.add(Row.Plain(it)) }

        info.entries.forEachIndexed { index, entry ->
            rows.add(Row.Header("Entry ${index + 1} of ${info.entryCount}"))
            rows.add(Row.Field("Alias", entry.alias))
            rows.add(Row.Field("Entry type", entry.entryType))
            entry.creationDate?.let { rows.add(Row.Field("Created", it)) }

            entry.certificates.forEachIndexed { ci, cert ->
                if (entry.certificates.size > 1) {
                    rows.add(Row.Header("  Certificate ${ci + 1} of ${entry.certificates.size}"))
                }
                rows.add(Row.Field("Subject", cert.subject))
                rows.add(Row.Field("Issuer", cert.issuer))
                rows.add(Row.Field("Serial", cert.serialNumber))
                rows.add(Row.Field("Valid from", cert.validFrom))
                rows.add(
                    Row.Field(
                        "Valid until",
                        cert.validUntil + if (cert.expired) "  ⚠️ EXPIRED / NOT YET VALID" else ""
                    )
                )
                rows.add(Row.Field("Version", "v${cert.version}"))
                rows.add(Row.Field("Signature algorithm", cert.signatureAlgorithm))
                rows.add(Row.Field("Public key", cert.publicKeyInfo))
                rows.add(Row.Field("Public key (PEM)", cert.publicKeyPem))
                rows.add(Row.Field("SHA-256 fingerprint", cert.sha256))
                rows.add(Row.Field("SHA-1 fingerprint", cert.sha1))
                rows.add(Row.Field("MD5 fingerprint", cert.md5))
            }

            entry.privateKeyNote?.let { rows.add(Row.Plain(it)) }
            entry.privateKey?.let { pk ->
                rows.add(Row.Header("  🔓 Private key"))
                rows.add(
                    Row.Plain(
                        "⚠️ SECRET — anyone with this key can sign as you. " +
                            "Do not share it."
                    )
                )
                rows.add(Row.Field("Key algorithm", pk.algorithm))
                pk.sizeBits?.let { rows.add(Row.Field("Key size", "$it-bit")) }
                rows.add(Row.Field("Key format", pk.format))
                rows.add(Row.Field("Private key (PKCS#8 PEM)", pk.pem))
            }
        }
        if (info.entries.isEmpty()) {
            rows.add(Row.Plain("(This keystore has no entries.)"))
        }
        rows.add(
            Row.Plain(
                "Tip: SHA-1 / SHA-256 of the signing cert are the fingerprints " +
                    "Firebase & Google APIs ask for. Tap Copy on any row."
            )
        )
        return rows
    }

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
                sb.appendLine(cert.publicKeyPem)
                sb.appendLine("  Fingerprints:")
                sb.appendLine("    SHA-256:   ${cert.sha256}")
                sb.appendLine("    SHA-1:     ${cert.sha1}")
                sb.appendLine("    MD5:       ${cert.md5}")
                sb.appendLine()
            }

            entry.privateKeyNote?.let { sb.appendLine("  $it").also { sb.appendLine() } }
            entry.privateKey?.let { pk ->
                sb.appendLine("  🔓 Private key  ⚠️ SECRET — do not share")
                sb.appendLine("  Algorithm:   ${pk.algorithm}")
                pk.sizeBits?.let { sb.appendLine("  Key size:    $it-bit") }
                sb.appendLine("  Format:      ${pk.format}")
                sb.appendLine(pk.pem)
                sb.appendLine()
            }
        }
        sb.appendLine("──────────────────────────────")
        sb.appendLine("Tip: SHA-1 / SHA-256 of the signing cert are the")
        sb.appendLine("fingerprints Firebase & Google APIs ask for.")
        return sb.toString()
    }
}
