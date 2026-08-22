package com.apkbuilder.core

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

/**
 * Android ships its own stripped-down security provider already registered
 * under the name "BC" (no full X.509 CertificateFactory support), which
 * shadows the real Bouncy Castle jar on the classpath and makes e.g.
 * `CertificateFactory.getInstance("X.509")` fail with "X.509 not found".
 * Call [ensureInstalled] before touching any BC-backed API.
 */
internal object BcProvider {
    @Volatile private var installed = false

    fun ensureInstalled() {
        if (installed) return
        synchronized(this) {
            if (installed) return
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.insertProviderAt(BouncyCastleProvider(), 1)
            installed = true
        }
    }
}
