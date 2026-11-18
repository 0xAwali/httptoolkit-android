package tech.httptoolkit.android.manual

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tech.httptoolkit.android.R
import tech.httptoolkit.android.normalizeCertificateFingerprint
import tech.httptoolkit.android.portfilter.MAX_PORT
import tech.httptoolkit.android.portfilter.MIN_PORT
import tech.httptoolkit.android.ui.AppConstants
import tech.httptoolkit.android.ui.DmSansFontFamily

private val TITLE_FONT_SIZE = 24.sp
private val TITLE_LETTER_SPACING = (-0.03).sp
private val PROGRESS_INDICATOR_SIZE = 20.dp

/**
 * Lets the user connect to a HTTP Toolkit instance by typing its address, for cases where
 * scanning a QR code isn't possible (no camera, remote machine, a custom proxy port, or an
 * emulator talking to the host through 10.0.2.2).
 */
@Composable
fun ManualConnectScreen(
    initialHost: String,
    initialPort: Int,
    state: ManualConnectState,
    onConnect: (host: String, port: Int, fingerprint: String?) -> Unit,
    onDismissError: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var addressInput by rememberSaveable { mutableStateOf(initialHost) }
    var portInput by rememberSaveable { mutableStateOf(initialPort.toString()) }
    var fingerprintInput by rememberSaveable { mutableStateOf("") }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var validateAttempted by rememberSaveable { mutableStateOf(false) }

    val isConnecting = state is ManualConnectState.Connecting

    val parsedAddress = remember(addressInput) { ProxyAddressParser.parse(addressInput) }
    val isConnectUrl = remember(addressInput) { ProxyAddressParser.isConnectUrl(addressInput) }
    val parsedPort = remember(portInput) {
        portInput.toIntOrNull()?.takeIf { it in MIN_PORT..MAX_PORT }
    }
    val normalizedFingerprint = remember(fingerprintInput) {
        if (fingerprintInput.isBlank()) null else normalizeCertificateFingerprint(fingerprintInput)
    }

    val addressError = when {
        isConnectUrl -> R.string.manual_error_use_qr
        parsedAddress == null -> R.string.manual_error_address_invalid
        else -> null
    }
    val portError = if (parsedPort == null) R.string.manual_error_port_invalid else null
    val fingerprintIsInvalid = fingerprintInput.isNotBlank() && normalizedFingerprint == null

    // Don't shout about an empty form before the user has tried anything, but do show a problem
    // as soon as there's something wrong with what they've typed.
    val showAddressError = addressError != null && (validateAttempted || addressInput.isNotBlank())
    val showPortError = portError != null && (validateAttempted || portInput.isNotBlank())

    fun submit() {
        validateAttempted = true

        // Make sure the fingerprint error isn't hidden inside a collapsed section:
        if (fingerprintIsInvalid) {
            showAdvanced = true
            return
        }

        val address = parsedAddress ?: return
        val port = parsedPort ?: return
        if (isConnectUrl) return

        onConnect(address.host, port, normalizedFingerprint)
    }

    fun onAddressChanged(newValue: String) {
        addressInput = newValue
        // If the address itself includes a port ("192.168.0.10:8080") use that, and keep the
        // separate port field in sync so what the user sees is what they'll connect to.
        ProxyAddressParser.parse(newValue)?.port?.let { portInput = it.toString() }
        onDismissError()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = AppConstants.spacingSmall, end = AppConstants.spacingLarge, top = AppConstants.spacingSmall),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.cd_back)
                )
            }
            Text(
                text = stringResource(R.string.manual_connect_title),
                fontSize = TITLE_FONT_SIZE,
                fontFamily = DmSansFontFamily,
                fontWeight = FontWeight.Bold,
                letterSpacing = TITLE_LETTER_SPACING,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AppConstants.spacingLarge, vertical = AppConstants.spacingSmall),
            verticalArrangement = Arrangement.spacedBy(AppConstants.spacingLarge)
        ) {
            Text(
                text = stringResource(R.string.manual_connect_explanation),
                fontSize = 16.sp,
                fontFamily = DmSansFontFamily,
                color = MaterialTheme.colorScheme.onBackground
            )

            OutlinedTextField(
                value = addressInput,
                onValueChange = ::onAddressChanged,
                label = { Text(stringResource(R.string.manual_address_label)) },
                placeholder = { Text(stringResource(R.string.manual_address_hint)) },
                singleLine = true,
                isError = showAddressError,
                enabled = !isConnecting,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next
                ),
                supportingText = {
                    Text(
                        text = if (showAddressError) stringResource(addressError!!)
                        else stringResource(R.string.manual_address_supporting),
                        fontFamily = DmSansFontFamily
                    )
                },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = portInput,
                onValueChange = {
                    portInput = it
                    onDismissError()
                },
                label = { Text(stringResource(R.string.manual_port_label)) },
                singleLine = true,
                isError = showPortError,
                enabled = !isConnecting,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                supportingText = if (showPortError) {
                    { Text(stringResource(portError!!), fontFamily = DmSansFontFamily) }
                } else null,
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppConstants.spacingTiny),
                horizontalArrangement = Arrangement.spacedBy(AppConstants.spacingSmall)
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = stringResource(R.string.manual_certificate_warning),
                    fontSize = AppConstants.textSizeCaption,
                    fontFamily = DmSansFontFamily,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            Column {
                TextButton(
                    onClick = { showAdvanced = !showAdvanced },
                    contentPadding = PaddingValues(horizontal = AppConstants.spacingTiny),
                    enabled = !isConnecting
                ) {
                    Icon(
                        imageVector = if (showAdvanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(AppConstants.spacingTiny))
                    Text(
                        text = stringResource(R.string.manual_advanced),
                        fontFamily = DmSansFontFamily
                    )
                }

                AnimatedVisibility(
                    visible = showAdvanced,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    OutlinedTextField(
                        value = fingerprintInput,
                        onValueChange = {
                            fingerprintInput = it
                            onDismissError()
                        },
                        label = { Text(stringResource(R.string.manual_fingerprint_label)) },
                        singleLine = false,
                        maxLines = 3,
                        isError = fingerprintIsInvalid,
                        enabled = !isConnecting,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        supportingText = {
                            Text(
                                text = if (fingerprintIsInvalid) {
                                    stringResource(R.string.manual_error_fingerprint_invalid)
                                } else {
                                    stringResource(R.string.manual_fingerprint_supporting)
                                },
                                fontFamily = DmSansFontFamily
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = AppConstants.spacingSmall)
                    )
                }
            }

            (state as? ManualConnectState.Failed)?.let { failure ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppConstants.spacingSmall)
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = stringResource(failure.error.messageRes),
                        fontSize = AppConstants.textSizeCaption,
                        fontFamily = DmSansFontFamily,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(
                    // The larger of the keyboard and the nav bar, so the button stays reachable
                    // above the keyboard without double-counting the nav bar inset:
                    WindowInsets.ime.union(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                )
                .padding(horizontal = AppConstants.spacingLarge, vertical = AppConstants.spacingNormal)
        ) {
            Button(
                onClick = { submit() },
                enabled = !isConnecting,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(AppConstants.buttonHeight)
            ) {
                if (isConnecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(PROGRESS_INDICATOR_SIZE),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(AppConstants.spacingSmall))
                    Text(
                        text = stringResource(R.string.manual_connecting),
                        fontSize = AppConstants.textSizeBodyLarge,
                        fontFamily = DmSansFontFamily,
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    Text(
                        text = stringResource(R.string.manual_connect_action),
                        fontSize = AppConstants.textSizeBodyLarge,
                        fontFamily = DmSansFontFamily,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
