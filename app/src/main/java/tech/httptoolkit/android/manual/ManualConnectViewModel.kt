package tech.httptoolkit.android.manual

import android.app.Application
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.sentry.Sentry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import tech.httptoolkit.android.HttpToolkitApplication
import tech.httptoolkit.android.NoSupportedProxyCertificateException
import tech.httptoolkit.android.ProxyAuthRequiredException
import tech.httptoolkit.android.ProxyCertificateMismatchException
import tech.httptoolkit.android.ProxyConfig
import tech.httptoolkit.android.ProxyHostNotFoundException
import tech.httptoolkit.android.R
import tech.httptoolkit.android.ResponseException
import tech.httptoolkit.android.getManualProxyConfig
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

private const val TAG = "ManualConnect"

/**
 * A failure that we can explain to the user in their own terms, rather than just "it failed".
 * Each case keeps its message in resources so the screen stays fully translatable.
 */
enum class ManualConnectError(@param:StringRes val messageRes: Int) {
    HOST_NOT_FOUND(R.string.manual_error_host_not_found),
    CONNECTION_REFUSED(R.string.manual_error_connection_refused),
    TIMED_OUT(R.string.manual_error_timed_out),
    CONNECTION_FAILED(R.string.manual_error_connection_failed),
    PROXY_AUTH_REQUIRED(R.string.manual_error_proxy_auth_required),
    NO_PROXY_CERTIFICATE(R.string.manual_error_no_proxy_certificate),
    CERTIFICATE_MISMATCH(R.string.manual_error_certificate_mismatch),
    UNKNOWN(R.string.manual_error_unknown)
}

sealed interface ManualConnectState {
    data object Idle : ManualConnectState
    data object Connecting : ManualConnectState
    data class Failed(val error: ManualConnectError) : ManualConnectState
    data class Connected(val config: ProxyConfig) : ManualConnectState
}

/**
 * Retained across configuration changes, so that rotating the device during (or after) a
 * connection attempt doesn't lose the result or start a second probe.
 */
class ManualConnectViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as HttpToolkitApplication

    /** Prefill from the last address the user tried, so retrying is a one-tap job. */
    val initialHost: String = app.lastManualProxyHost ?: ""
    val initialPort: Int = app.lastManualProxyPort

    var state: ManualConnectState by mutableStateOf(ManualConnectState.Idle)
        private set

    fun connect(host: String, port: Int, fingerprint: String?) {
        if (state is ManualConnectState.Connecting) return

        state = ManualConnectState.Connecting
        app.lastManualProxyHost = host
        app.lastManualProxyPort = port

        viewModelScope.launch {
            try {
                val config = getManualProxyConfig(host, port, fingerprint)
                Log.i(TAG, "Manual proxy config validated for $host:$port")
                state = ManualConnectState.Connected(config)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Manual connection to $host:$port failed", e)

                val error = classifyFailure(e)
                state = ManualConnectState.Failed(error)

                // Only report genuinely unexpected failures: wrong addresses and unreachable
                // proxies are user input problems, not bugs worth sending to Sentry.
                if (error == ManualConnectError.UNKNOWN) Sentry.captureException(e)
            }
        }
    }

    /** Clears a failure once the user starts editing the form again. */
    fun dismissError() {
        if (state is ManualConnectState.Failed) state = ManualConnectState.Idle
    }

    private fun classifyFailure(e: Exception): ManualConnectError = when {
        e is ProxyHostNotFoundException || e is UnknownHostException ->
            ManualConnectError.HOST_NOT_FOUND

        e is ProxyCertificateMismatchException -> ManualConnectError.CERTIFICATE_MISMATCH

        e is ProxyAuthRequiredException -> ManualConnectError.PROXY_AUTH_REQUIRED

        // The proxy answered, but isn't one whose CA we know how to fetch:
        e is NoSupportedProxyCertificateException || e is ResponseException ->
            ManualConnectError.NO_PROXY_CERTIFICATE

        e is SocketTimeoutException -> ManualConnectError.TIMED_OUT

        // ConnectException covers "connection refused" and "no route to host":
        e is ConnectException -> ManualConnectError.CONNECTION_REFUSED

        e is IOException -> ManualConnectError.CONNECTION_FAILED

        else -> ManualConnectError.UNKNOWN
    }
}
