package com.example.bitperfectplayer

import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security
import java.util.Properties

/**
 * Singleton that builds and caches a properly configured jcifs-ng CIFSContext.
 *
 * jcifs-ng 2.x dropped the System.setProperty() configuration API that jcifs 1.x used.
 * A CIFSContext must be created explicitly and passed to every SmbFile constructor —
 * without it the library uses very conservative defaults that break listing and auth.
 */
object SmbContext {

    init {
        // Register BouncyCastle for MD4 support (required for NTLM auth in jcifs-ng)
        Security.removeProvider("BC")
        Security.addProvider(BouncyCastleProvider())
    }

    @Volatile
    private var context: CIFSContext? = null

    /**
     * Returns a CIFSContext configured for broad SMB compatibility:
     * - SMB1 through SMB3.1.1 (minVersion=1 lets the server negotiate down if needed)
     * - NTLM auth enabled (required for most home NAS / Windows shares)
     * - Guest / anonymous fallback allowed
     * - Reasonable timeouts for a TV UI
     */
    fun get(): CIFSContext = context ?: synchronized(this) {
        context ?: buildContext().also { context = it }
    }

    /**
     * Build a context with explicit credentials embedded in the URL.
     * Used when browsing a share that requires a username/password not in the URL.
     */
    fun getWithCredentials(domain: String = "", user: String, pass: String): CIFSContext =
        get().withCredentials(NtlmPasswordAuthenticator(domain, user, pass))

    /**
     * Parses a URI to extract credentials if present, returning a properly configured CIFSContext.
     * jcifs-ng requires the context to hold the credentials; they are ignored if only in the URL string.
     */
    fun getContextForUri(uri: String): CIFSContext {
        try {
            // Use android.net.Uri as it's more lenient with spaces and special chars than java.net.URI
            val parsedUri = android.net.Uri.parse(uri)
            val userInfo = parsedUri.userInfo
            if (userInfo != null && userInfo.contains(":")) {
                val parts = userInfo.split(":", limit = 2)
                val user = android.net.Uri.decode(parts[0])
                val pass = android.net.Uri.decode(parts[1])
                return getWithCredentials(user = user, pass = pass)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return get()
    }

    private fun buildContext(): CIFSContext {
        val props = Properties().apply {
            // Protocol version range — let the server negotiate
            setProperty("jcifs.smb.client.minVersion", "SMB1")
            setProperty("jcifs.smb.client.maxVersion", "SMB311")

            // Authentication
            setProperty("jcifs.smb.client.enableSMB2", "true")
            setProperty("jcifs.smb.client.dfs.disabled", "true") // Disable DFS to avoid "Access is denied" on many simple shares

            // Timeouts (ms)
            setProperty("jcifs.smb.client.connTimeout", "15000")
            setProperty("jcifs.smb.client.responseTimeout", "30000")
            setProperty("jcifs.smb.client.sessionTimeout", "30000")

            // Allow guest / anonymous access for public shares
            setProperty("jcifs.smb.client.guestUsername", "GUEST")
            setProperty("jcifs.smb.client.guestPassword", "")

            // Authentication settings for modern servers
            setProperty("jcifs.smb.client.useRawNTLM", "true")
            setProperty("jcifs.smb.lmCompatibility", "3") // Force NTLMv2
            setProperty("jcifs.smb.client.useExtendedSecurity", "true")

            // Critical for listing shares on many modern servers/NAS
            setProperty("jcifs.smb.client.ipcSigningEnforced", "false")
            setProperty("jcifs.smb.client.signingEnforced", "false")
            setProperty("jcifs.smb.client.signingPreferred", "false")

            // Buffer sizes for streaming audio
            setProperty("jcifs.smb.client.rcv_buf_size", "131072")
            setProperty("jcifs.smb.client.snd_buf_size", "131072")
        }
        return BaseContext(PropertyConfiguration(props))
    }
}
