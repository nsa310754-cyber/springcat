package com.springcat.ide.core.signing

import org.bouncycastle.cert.jcajce.JcaCertStore
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Minimal, correct JAR (v1) signer used for signing Android App Bundles (`.aab`).
 *
 * An `.aab` is a ZIP; Play accepts bundles signed with a standard JAR
 * signature — exactly what `jarsigner` produces. This implementation writes a
 * `META-INF/MANIFEST.MF`, a `CERT.SF` signature file and a PKCS#7 `CERT.RSA`
 * (or `CERT.EC`) block, digesting the very bytes it writes so the output is
 * self-consistent and verifiable.
 */
class JarSigner {

    fun sign(
        input: File,
        output: File,
        privateKey: PrivateKey,
        certificate: X509Certificate,
    ): SigningResult = try {
        output.outputStream().buffered().use { raw ->
            signTo(input, raw, privateKey, certificate)
        }
        SigningResult.Success(output, "JAR v1")
    } catch (t: Throwable) {
        SigningResult.Failure(t.message ?: t.javaClass.simpleName)
    }

    private fun signTo(
        input: File,
        out: OutputStream,
        privateKey: PrivateKey,
        certificate: X509Certificate,
    ) {
        // 1. Read every non-signature entry and remember its bytes.
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(input.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && !isSignatureEntry(entry.name)) {
                    entries[entry.name] = zis.readBytes()
                }
                entry = zis.nextEntry
            }
        }

        val sha = MessageDigest.getInstance("SHA-256")
        val b64 = Base64.getEncoder()

        // 2. Build MANIFEST.MF: a main section plus one section per file entry.
        val manifestSections = LinkedHashMap<String, String>()
        val mainSection = attr("Manifest-Version", "1.0") + attr("Created-By", "SpringCat")
        val manifestBuilder = StringBuilder(mainSection).append(CRLF)
        for ((name, bytes) in entries) {
            val digest = b64.encodeToString(sha.digest(bytes))
            val section = attr("Name", name) + attr("SHA-256-Digest", digest)
            manifestSections[name] = section
            manifestBuilder.append(section).append(CRLF)
        }
        val manifestBytes = manifestBuilder.toString().toByteArray(Charsets.UTF_8)

        // 3. Build CERT.SF: digests of the manifest as a whole and per section.
        val mainAttrDigest = b64.encodeToString(sha.digest((mainSection + CRLF).toByteArray(Charsets.UTF_8)))
        val manifestDigest = b64.encodeToString(sha.digest(manifestBytes))
        val sf = StringBuilder()
            .append(attr("Signature-Version", "1.0"))
            .append(attr("SHA-256-Digest-Manifest-Main-Attributes", mainAttrDigest))
            .append(attr("SHA-256-Digest-Manifest", manifestDigest))
            .append(CRLF)
        for ((name, section) in manifestSections) {
            // Digest the exact bytes of this section as they appear in the manifest,
            // including the blank line that terminates it.
            val sectionDigest = b64.encodeToString(sha.digest((section + CRLF).toByteArray(Charsets.UTF_8)))
            sf.append(attr("Name", name)).append(attr("SHA-256-Digest", sectionDigest)).append(CRLF)
        }
        val sfBytes = sf.toString().toByteArray(Charsets.UTF_8)

        // 4. PKCS#7 detached signature over CERT.SF.
        val signatureBlock = pkcs7(sfBytes, privateKey, certificate)
        val blockExt = if (privateKey.algorithm.equals("EC", ignoreCase = true)) "EC" else "RSA"

        // 5. Emit the signed ZIP. Manifest first, then signature files, then payload.
        ZipOutputStream(out).use { zos ->
            zos.putStored("META-INF/MANIFEST.MF", manifestBytes)
            zos.putStored("META-INF/CERT.SF", sfBytes)
            zos.putStored("META-INF/CERT.$blockExt", signatureBlock)
            for ((name, bytes) in entries) {
                zos.putDeflated(name, bytes)
            }
        }
    }

    private fun pkcs7(content: ByteArray, key: PrivateKey, cert: X509Certificate): ByteArray {
        val algorithm = if (key.algorithm.equals("EC", ignoreCase = true)) "SHA256withECDSA" else "SHA256withRSA"
        val signer = JcaContentSignerBuilder(algorithm).build(key)
        val generator = CMSSignedDataGenerator().apply {
            addSignerInfoGenerator(
                JcaSignerInfoGeneratorBuilder(JcaDigestCalculatorProviderBuilder().build())
                    .build(signer, cert),
            )
            addCertificates(JcaCertStore(listOf(cert)))
        }
        // Detached signature: the content is CERT.SF, kept out of the block itself.
        val signed = generator.generate(CMSProcessableByteArray(content), false)
        return signed.getEncoded()
    }

    private fun attr(key: String, value: String): String = wrap("$key: $value")

    /** Wrap a manifest line to <= 72 bytes per physical line, per the JAR spec. */
    private fun wrap(line: String): String {
        val bytes = line.toByteArray(Charsets.UTF_8)
        if (bytes.size <= MAX_LINE) return line + CRLF
        val sb = StringBuilder()
        var start = 0
        var first = true
        while (start < bytes.size) {
            val limit = if (first) MAX_LINE else MAX_LINE - 1
            val end = minOf(start + limit, bytes.size)
            if (!first) sb.append(' ')
            sb.append(String(bytes, start, end - start, Charsets.UTF_8))
            sb.append(CRLF)
            start = end
            first = false
        }
        return sb.toString()
    }

    private fun ZipOutputStream.putStored(name: String, data: ByteArray) {
        val entry = ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = data.size.toLong()
            compressedSize = data.size.toLong()
            crc = java.util.zip.CRC32().apply { update(data) }.value
        }
        putNextEntry(entry)
        write(data)
        closeEntry()
    }

    private fun ZipOutputStream.putDeflated(name: String, data: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(data)
        closeEntry()
    }

    private fun isSignatureEntry(name: String): Boolean {
        val upper = name.uppercase()
        return upper == "META-INF/MANIFEST.MF" ||
            (upper.startsWith("META-INF/") &&
                (upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".EC") || upper.endsWith(".DSA")))
    }

    companion object {
        private const val CRLF = "\r\n"
        private const val MAX_LINE = 72
    }
}
