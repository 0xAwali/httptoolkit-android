package tech.httptoolkit.android.manual

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import tech.httptoolkit.android.IntentExtras
import tech.httptoolkit.android.ui.HttpToolkitTheme

/**
 * Collects a proxy address from the user, validates it against the proxy, and returns the
 * resulting [tech.httptoolkit.android.ProxyConfig] to [tech.httptoolkit.android.main.MainActivity],
 * which then runs the usual VPN & certificate setup.
 */
class ManualConnectActivity : ComponentActivity() {

    private val viewModel: ManualConnectViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            HttpToolkitTheme {
                val state = viewModel.state

                // Hand the validated config back as soon as the proxy answers. This also runs
                // after a configuration change, which is harmless (we just finish again).
                LaunchedEffect(state) {
                    if (state is ManualConnectState.Connected) {
                        setResult(RESULT_OK, Intent().putExtra(
                            IntentExtras.PROXY_CONFIG_EXTRA,
                            state.config
                        ))
                        finish()
                    }
                }

                ManualConnectScreen(
                    initialHost = viewModel.initialHost,
                    initialPort = viewModel.initialPort,
                    state = state,
                    onConnect = viewModel::connect,
                    onDismissError = viewModel::dismissError,
                    onBack = { finish() }
                )
            }
        }
    }
}
