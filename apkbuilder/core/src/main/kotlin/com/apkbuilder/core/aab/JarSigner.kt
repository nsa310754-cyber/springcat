package com.apkbuilder.core.aab

import com.apkbuilder.core.BcProvider
import com.apkbuilder.core.SigningKey
import org.bouncycastle.cert.jcajce.JcaCertStore
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Signs a ZIP (used here for an Android App Bundle) with a standard v1 / JAR
 * signature — the scheme `jarsigner` produces and the format Google Play
 * requires for uploaded `.aab` files. Google's `apksig` library only signs
 * APKs (it rejects a bundle), so this rolls the JAR-signing pieces directly:
 * a `META-INF/MANIFEST.MF` of per-entry SHA-256 digests, a `.SF` signature
 * file, and a PKCS#7 (`.RSA`) detached signature over it via Bouncy Castle CMS.
 */
object JarSigner {

    private const val MAX_LINE = 70 // content bytes before wrapping; keeps every line <= 72 bytes with CRLF

    fun sign(bundleBytes: ByteArray, signingKey: SigningKey): ByteArray {
        BcProvider.ensureInstalled()
        val privateKey: PrivateKey = signingKey.privateKey
        val cert: X509Certificate = signingKey.certificate

        // Read every existing (non-signature) entry, preserving content.
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bundleBytes)).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                if (!e.isDirectory) {
                    val name = e.name
                    if (!name.startsWith("META-INF/") || !(name.endsWith(".MF") || name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".EC") || name.endsWith(".DSA"))) {
                        entries[name] = zis.readBytes()
                    }
                }
                e = zis.nextEntry
            }
        }

        val sha = MessageDigest.getInstance("SHA-256")

        // ---- MANIFEST.MF ----
        val manifestMain = "Manifest-Version: 1.0\r\nCreated-By: APK Builder\r\n\r\n".toByteArray(Charsets.UTF_8)
        val perEntrySections = LinkedHashMap<String, ByteArray>()
        val manifestBody = ByteArrayOutputStream()
        manifestBody.write(manifestMain)
        for ((name, content) in entries) {
            val digest = Base64.getEncoder().encodeToString(sha.digest(content))
            val section = buildSection(name, "SHA-256-Digest", digest)
            perEntrySections[name] = section
            manifestBody.write(section)
        }
        val manifestBytes = manifestBody.toByteArray()

        // ---- .SF ----
        val sfMainText = buildString {
            append("Signature-Version: 1.0\r\n")
            append("Created-By: APK Builder\r\n")
            append("SHA-256-Digest-Manifest: ")
            append(Base64.getEncoder().encodeToString(sha.digest(manifestBytes)))
            append("\r\n")
            append("SHA-256-Digest-Manifest-Main-Attributes: ")
            append(Base64.getEncoder().encodeToString(sha.digest(manifestMain)))
            append("\r\n\r\n")
        }.toByteArray(Charsets.UTF_8)
        val sfBody = ByteArrayOutputStream()
        sfBody.write(sfMainText)
        for ((name, section) in perEntrySections) {
            val digest = Base64.getEncoder().encodeToString(sha.digest(section))
            sfBody.write(buildSection(name, "SHA-256-Digest", digest))
        }
        val sfBytes = sfBody.toByteArray()

        // ---- PKCS#7 detached signature over the .SF ----
        val rsaBytes = pkcs7(sfBytes, privateKey, cert)

        // ---- Rewrite the zip with signature files first ----
        val alias = signingKey.alias.uppercase().take(8).filter { it.isLetterOrDigit() }.ifEmpty { "CERT" }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            writeEntry(zos, "META-INF/MANIFEST.MF", manifestBytes)
            writeEntry(zos, "META-INF/$alias.SF", sfBytes)
            writeEntry(zos, "META-INF/$alias.RSA", rsaBytes)
            for ((name, content) in entries) {
                writeEntry(zos, name, content)
            }
        }
        return out.toByteArray()
    }

    private fun buildSection(name: String, digestKey: String, digestValue: String): ByteArray {
        val sb = StringBuilder()
        appendWrapped(sb, "Name: $name")
        appendWrapped(sb, "$digestKey: $digestValue")
        sb.append("\r\n")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    /** Appends a header line wrapped to <=72 bytes/line (continuation lines begin with a space), CRLF-terminated. */
    private fun appendWrapped(sb: StringBuilder, line: String) {
        val bytes = line.toByteArray(Charsets.UTF_8)
        if (bytes.size <= MAX_LINE) {
            sb.append(line).append("\r\n")
            return
        }
        var start = 0
        var first = true
        while (start < bytes.size) {
            val limit = if (first) MAX_LINE else MAX_LINE - 1
            val end = minOf(start + limit, bytes.size)
            val chunk = String(bytes, start, end - start, Charsets.UTF_8)
            if (first) {
                sb.append(chunk)
                first = false
            } else {
                sb.append(' ').append(chunk)
            }
            sb.append("\r\n")
            start = end
        }
    }

    private fun pkcs7(content: ByteArray, privateKey: PrivateKey, cert: X509Certificate): ByteArray {
        val gen = CMSSignedDataGenerator()
        val signer = JcaContentSignerBuilder("SHA256withRSA").setProvider(BouncyCastleProvider.PROVIDER_NAME).build(privateKey)
        val digestProvider = JcaDigestCalculatorProviderBuilder().setProvider(BouncyCastleProvider.PROVIDER_NAME).build()
        gen.addSignerInfoGenerator(JcaSignerInfoGeneratorBuilder(digestProvider).build(signer, cert))
        gen.addCertificates(JcaCertStore(listOf(cert)))
        val signed = gen.generate(CMSProcessableByteArray(content), false) // detached
        return signed.encoded
    }

    private fun writeEntry(zos: ZipOutputStream, name: String, content: ByteArray) {
        // Deflated: the signature digests cover uncompressed content, so compression
        // doesn't affect validity, and it keeps the bundle close to its original size.
        zos.putNextEntry(ZipEntry(name))
        zos.write(content)
        zos.closeEntry()
    }
}
