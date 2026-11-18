package tech.httptoolkit.android

import android.app.KeyguardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat.getSystemService
import com.beust.klaxon.Klaxon
import io.sentry.Sentry
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit

private val TAG = formatTag("tech.httptoolkit.android.ProxySetup")

private const val PROXY_CONNECT_TIMEOUT_MS = 5_000
private const val PROXY_READ_TIMEOUT_MS = 3_000

private const val HTTP_TOOLKIT_CONFIG_URL = "http://android.httptoolkit.tech/config"
private const val HTTP_TOOLKIT_CERT_URL = "http://amiusing.httptoolkit.tech/certificate"

// Intercepting proxies serve their CA certificate at well-known fake hosts that they intercept
// themselves. Supporting these means manual setup works with more than just HTTP Toolkit, even
// though the rest of the app (VPN setup & certificate installation) is proxy-agnostic.
private const val BURP_CERT_URL = "http://burp/cert"

class ResponseException(message: String) : ConnectException(message)

/**
 * Thrown when a manually entered proxy host can't be resolved to any IP address.
 *
 * This is a distinct type so that the UI can explain the problem ("that address doesn't exist")
 * rather than just reporting a generic connection failure.
 */
class ProxyHostNotFoundException(val host: String) : ConnectException(
    "Could not resolve proxy host: $host"
)

/** The proxy is working, but requires credentials that we don't have (and can't prompt for). */
class ProxyAuthRequiredException : IOException("Proxy requires authentication")

/**
 * The proxy answered, but didn't provide a CA certificate through any endpoint we understand.
 * This means it either isn't an intercepting proxy at all, or its CA download is non-standard.
 */
class NoSupportedProxyCertificateException(cause: Throwable?) : IOException(
    "The proxy didn't return a supported CA certificate",
    cause
)

/**
 * Thrown when the proxy's certificate doesn't match the fingerprint that the user expected.
 * Separate from other CertificateExceptions so that a genuine mismatch is never confused with
 * "that address isn't an HTTP Toolkit proxy at all".
 */
class ProxyCertificateMismatchException(
    expectedFingerprint: String,
    foundFingerprint: String,
    address: String
) : CertificateException(
    "Proxy returned mismatched certificate: '$expectedFingerprint' != '$foundFingerprint' ($address)"
)

// Takes an android.httptoolkit.tech/connect URI, extracts & parses the connection config
// within, into a format ready for testing and then usage.
fun parseConnectUri(uri: Uri): ProxyInfo {
    val dataBase64 = uri.getQueryParameter("data")

    // Data is a JSON string, encoded as base64, to solve escaping & ensure that the
    // most popular standard barcode apps treat it as a single URL (some get confused by
    // JSON that contains ip addresses otherwise)
    val data = String(Base64.decode(dataBase64, Base64.URL_SAFE), StandardCharsets.UTF_8)
    Log.d(TAG, "URL data is $data")

    return Klaxon().parse<ProxyInfo>(data)
        ?: throw IllegalArgumentException("Invalid proxy JSON: $data")
}

suspend fun getProxyConfig(proxyInfo: ProxyInfo): ProxyConfig {
    Log.v(TAG, "Validating proxy info $proxyInfo")

    return getProxyConfigForAddresses(
        proxyInfo.addresses,
        proxyInfo.port,
        proxyInfo.certFingerprint,
        proxyInfo.localTunnelPort
    )
}

/**
 * Builds a config for a proxy that the user entered by hand, instead of scanning a QR code.
 *
 * QR codes include the expected certificate fingerprint, so we can verify that we're talking to
 * the right proxy. Manual configuration generally has no fingerprint to check against, so (unless
 * the user supplies one) the certificate returned by the proxy is trusted as-is - trust on first
 * use. The UI makes that trade-off clear before connecting.
 *
 * The host is resolved here rather than left to OkHttp, because [ProxyConfig.ip] is also used for
 * the device-wide HTTP proxy setting, which requires an IP address, not a hostname.
 */
suspend fun getManualProxyConfig(
    host: String,
    port: Int,
    expectedFingerprint: String? = null
): ProxyConfig {
    val addresses = withContext(Dispatchers.IO) {
        Log.i(TAG, "Resolving manual proxy host: $host")
        try {
            InetAddress.getAllByName(host)
                .mapNotNull { it.hostAddress }
                .distinct()
                // IPv4 first, since that's the most likely to work on typical home networks. All
                // of these addresses are then tested in parallel and the first success wins.
                .sortedBy { address -> if (address.contains(':')) 1 else 0 }
        } catch (e: UnknownHostException) {
            Log.w(TAG, "Could not resolve manual proxy host: $host", e)
            throw ProxyHostNotFoundException(host)
        }
    }

    if (addresses.isEmpty()) throw ProxyHostNotFoundException(host)

    return getProxyConfigForAddresses(addresses, port, expectedFingerprint)
}

private suspend fun getProxyConfigForAddresses(
    addresses: List<String>,
    port: Int,
    expectedFingerprint: String?,
    localTunnelPort: Int? = null
): ProxyConfig {
    // awaitFirst() never completes for an empty collection, so an empty config would otherwise
    // leave the app stuck in a permanent "Connecting" state:
    require(addresses.isNotEmpty()) { "No proxy addresses to test" }

    return withContext(Dispatchers.IO) {
        supervisorScope {
            Log.v(TAG, "Testing proxy addresses $addresses:$port")
            val proxyTests = addresses.map { address ->
                async { testProxyAddress(address, port, expectedFingerprint) }
            }

            Log.v(TAG, "Proxy tests started")

            // Return with the first working proxy config (cert & address)
            // (or throw if all addresses are unreachable/invalid)
            try {
                proxyTests.awaitFirst()
            } catch (e: Exception) {
                if (localTunnelPort == null) throw e

                // If all network connections fail, and we have a local ADB tunnel, fallback to
                // using that connection instead.
                testProxyAddress("127.0.0.1", localTunnelPort, expectedFingerprint)
            }
        }
    }
}

private suspend fun testProxyAddress(
    address: String,
    port: Int,
    expectedFingerprint: String?
): ProxyConfig {
    return withContext(Dispatchers.IO) {
        val certFactory = CertificateFactory.getInstance("X.509")

        Log.i(TAG, "Testing proxy $address:$port")
        checkProxyReachable(address, port)

        val httpClient = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(address, port)))
            .connectTimeout(PROXY_CONNECT_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(PROXY_READ_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .retryOnConnectionFailure(false)
            .build()

        try {
            val foundCert = findProxyCertificate(httpClient, certFactory)
            val foundCertFingerprint = getCertificateFingerprint(foundCert)

            if (expectedFingerprint == null) {
                // Manual setup without a fingerprint: trust on first use, which the UI warns about.
                Log.i(TAG, "No expected fingerprint provided, trusting certificate from $address")
                ProxyConfig(address, port, foundCert)
            } else if (foundCertFingerprint == expectedFingerprint) {
                ProxyConfig(address, port, foundCert)
            } else {
                throw ProxyCertificateMismatchException(
                    expectedFingerprint,
                    foundCertFingerprint,
                    address
                )
            }
        } catch (e: Exception) {
            Log.i(TAG, "Error testing proxy address $address: $e")
            throw e
        }
    }
}

/**
 * Opens a throwaway TCP connection before probing any HTTP endpoints, so that a host that's
 * unreachable (firewall, wrong address, proxy bound to localhost only) is reported as a
 * connection problem instead of a confusing certificate error.
 */
private fun checkProxyReachable(address: String, port: Int) {
    try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(address, port), PROXY_CONNECT_TIMEOUT_MS)
        }
    } catch (e: IOException) {
        Log.i(TAG, "Proxy $address:$port is unreachable: $e")
        throw e
    }
}

/**
 * Finds the proxy's CA certificate.
 *
 * HTTP Toolkit gets first refusal, since it's the most common setup and QR-based connections depend
 * on its config endpoint. If the proxy isn't HTTP Toolkit, we then try Burp Suite's well-known
 * certificate endpoint. We deliberately don't probe any other third-party CA endpoints: an
 * unsupported proxy simply reports that its certificate couldn't be downloaded.
 */
private suspend fun findProxyCertificate(
    httpClient: OkHttpClient,
    certFactory: CertificateFactory
): X509Certificate {
    val httpToolkitCert = try {
        fetchHttpToolkitCertificate(httpClient, certFactory)
    } catch (e: ProxyAuthRequiredException) {
        throw e
    } catch (e: Exception) {
        Log.i(TAG, "Proxy did not provide an HTTP Toolkit config: $e")
        null
    }

    if (httpToolkitCert != null) return httpToolkitCert

    return try {
        fetchRawCertificate(httpClient, BURP_CERT_URL, certFactory)
    } catch (e: ProxyAuthRequiredException) {
        throw e
    } catch (e: Exception) {
        throw NoSupportedProxyCertificateException(e)
    }
}

private suspend fun fetchHttpToolkitCertificate(
    httpClient: OkHttpClient,
    certFactory: CertificateFactory
): X509Certificate {
    return try {
        val configString = request(httpClient, HTTP_TOOLKIT_CONFIG_URL)
        val config = Klaxon().parse<ReceivedProxyConfig>(configString)
            ?: throw ResponseException("Proxy returned an invalid config response")

        parseCertificate(config.certificate.toByteArray(StandardCharsets.UTF_8), certFactory)
    } catch (e: ProxyAuthRequiredException) {
        throw e
    } catch (e: ResponseException) {
        // If we connected but the response was bad, maybe we're reconnecting to an app that isn't
        // explicitly expecting an Android client, or an older server. Retry requesting just the cert.
        parseCertificate(requestBytes(httpClient, HTTP_TOOLKIT_CERT_URL), certFactory)
    }
}

private suspend fun fetchRawCertificate(
    httpClient: OkHttpClient,
    url: String,
    certFactory: CertificateFactory
): X509Certificate {
    return parseCertificate(requestBytes(httpClient, url), certFactory)
}

/**
 * Parses a certificate in either DER or PEM form. CertificateFactory handles both, so the proxy
 * can serve whichever format it prefers (Burp serves its CA as DER).
 */
private fun parseCertificate(bytes: ByteArray, certFactory: CertificateFactory): X509Certificate {
    return certFactory.generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
}

suspend fun request(httpClient: OkHttpClient, url: String): String {
    return String(requestBytes(httpClient, url), StandardCharsets.UTF_8)
}

suspend fun requestBytes(httpClient: OkHttpClient, url: String): ByteArray {
    return withContext(Dispatchers.IO) {
        httpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (response.code == 407) throw ProxyAuthRequiredException()
            if (response.code != 200) {
                throw ResponseException("Proxy responded with non-200: ${response.code} ($url)")
            }
            response.body!!.bytes()
        }
    }
}

fun getCertificateFingerprint(cert: X509Certificate): String {
    val md = MessageDigest.getInstance("SHA-256")
    md.update(cert.publicKey.encoded)
    val fingerprint = md.digest()
    return Base64.encodeToString(fingerprint, Base64.NO_WRAP)
}

/**
 * Normalizes a user-entered SHA-256 fingerprint into the base64 format we use internally.
 *
 * Fingerprints are displayed in lots of different formats (hex with colons, plain hex, standard
 * base64, URL-safe base64, with or without padding) so we accept any of them, and return null
 * if the input can't be a SHA-256 fingerprint at all.
 */
fun normalizeCertificateFingerprint(fingerprint: String): String? {
    val compact = fingerprint.filterNot { it.isWhitespace() }
    if (compact.isEmpty()) return null

    // Most tools display fingerprints as (colon-separated) hex, but HTTP Toolkit itself uses
    // base64, so accept both and convert to the latter:
    val hex = compact.replace(":", "").replace("-", "")
    if (hex.length == 64 && hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
        val bytes = ByteArray(32) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    val unpadded = compact.trimEnd('=')
    val padded = unpadded + "=".repeat((4 - unpadded.length % 4) % 4)

    val bytes = try {
        Base64.decode(padded, Base64.NO_WRAP)
    } catch (e: IllegalArgumentException) {
        try {
            Base64.decode(padded, Base64.NO_WRAP or Base64.URL_SAFE)
        } catch (e: IllegalArgumentException) {
            return null
        }
    }

    return if (bytes.size == 32) Base64.encodeToString(bytes, Base64.NO_WRAP) else null
}


/**
 * Does the device have a PIN/pattern/password set? Relevant because if not, the cert
 * setup will require the user to add one. This is best guess - not 100% accurate.
 */
fun isDeviceSecured(context: Context): Boolean {
    val keyguardManager = getSystemService(context, KeyguardManager::class.java)

    return when {
        // If we can't get a keyguard manager for some reason, assume there's no pin set
        keyguardManager == null -> false
        // If possible, accurately report device status
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> keyguardManager.isDeviceSecure
        // Imperfect but close though: also returns true if the device has a locked SIM card.
        else -> keyguardManager.isKeyguardSecure
    }
}

/**
 * Returns the name of the cert store (if the cert is trusted) or null (if not)
 */
fun whereIsCertTrusted(proxyConfig: ProxyConfig): String? {
    val keyStore = KeyStore.getInstance("AndroidCAStore")
    keyStore.load(null, null)

    val proxyCertData = proxyConfig.certificate.encoded

    val aliases = keyStore.aliases()

    val proxyCertAliases = aliases.toList().filter { alias ->
        val storedCert = keyStore.getCertificate(alias)
        val certData = storedCert?.encoded
        return@filter certData != null && certData.contentEquals(proxyCertData)
    }

    Log.i(TAG, "Proxy cert aliases: $proxyCertAliases")

    return when {
        proxyCertAliases.isEmpty() -> null
        proxyCertAliases.any { alias -> alias.startsWith("system:") } -> "system"
        proxyCertAliases.any { alias -> alias.startsWith("user:") } -> "user"
        else -> {
            Sentry.captureMessage("Cert has no recognizable aliases")
            return "unknown-store"
        }
    }
}