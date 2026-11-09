package io.github.mcl1080.cameracontrol

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mcl1080.cameracontrol.location.LocationSync
import io.github.mcl1080.cameracontrol.location.LocationSyncStatus
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    private val viewModel: LocationViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    val locationSync by viewModel.locationSync.collectAsStateWithLifecycle()
                    // Location sync needs Bluetooth and location permissions; ask, then run the action.
                    var pendingLocationAction by remember { mutableStateOf<(() -> Unit)?>(null) }
                    val permissionLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions(),
                    ) {
                        if (hasLocationSyncPermissions(this@MainActivity)) {
                            pendingLocationAction?.invoke()
                        } else {
                            LocationSync.update { s -> s.copy(message = "Location and Bluetooth permissions are needed for location sync") }
                        }
                        pendingLocationAction = null
                    }
                    // "Allow all the time" location lets sync restart by itself after a reboot.
                    fun refreshBackgroundAllowed() {
                        val allowed = canStartLocationSyncInBackground(this@MainActivity)
                        LocationSync.update { s -> s.copy(backgroundAllowed = allowed) }
                    }
                    LifecycleResumeEffect(Unit) {
                        refreshBackgroundAllowed()
                        onPauseOrDispose { }
                    }
                    val backgroundLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission(),
                    ) { refreshBackgroundAllowed() }
                    fun withLocationPermissions(action: () -> Unit) {
                        if (hasLocationSyncPermissions(this@MainActivity)) {
                            action()
                        } else {
                            pendingLocationAction = action
                            permissionLauncher.launch(locationSyncPermissions())
                        }
                    }
                    val locationActions = LocationActions(
                        onPair = { withLocationPermissions(viewModel::pairForLocation) },
                        onEnable = { on -> if (on) withLocationPermissions { viewModel.setLocationSync(true) } else viewModel.setLocationSync(false) },
                        onInterval = viewModel::setLocationInterval,
                        onForget = viewModel::forgetLocationPairing,
                        onAllowBackground = {
                            withLocationPermissions { backgroundLauncher.launch(BACKGROUND_LOCATION) }
                        },
                    )

                    Column(
                        Modifier
                            .fillMaxSize()
                            .safeDrawingPadding()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            stringResource(R.string.app_name),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        LocationScreen(locationSync, locationActions)
                    }
                }
            }
        }
    }
}

private fun locationSummary(s: LocationSyncStatus): String = when {
    s.pairing -> "Pairing…"
    !s.paired -> "Not set up"
    !s.running -> "Off"
    s.connected -> "Sending every ${intervalLabel(s.intervalSeconds)}"
    else -> "Waiting for the camera"
}

private fun intervalLabel(seconds: Int) = if (seconds < 60) "$seconds s" else "${seconds / 60} min"

/** A numbered instruction. */
@Composable
private fun Step(number: Int, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text("$number", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimary)
        }
        Spacer(Modifier.size(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

class LocationActions(
    val onPair: () -> Unit,
    val onEnable: (Boolean) -> Unit,
    val onInterval: (Int) -> Unit,
    val onForget: () -> Unit,
    val onAllowBackground: () -> Unit,
)

/** Where each brand's Bluetooth pairing mode is, roughly; menus differ between models. */
private val PAIRING_HOW_TO = listOf(
    "Nikon" to "Setup > Connect to smart device > Pairing (Bluetooth) > Start pairing",
    "Sony" to "Network > Bluetooth: turn Bluetooth Function on, then Pairing; also turn on Location Info. Link",
    "Canon" to "Wireless settings > Connect to smartphone > Add a device to connect",
    "Fujifilm" to "Network/USB settings > Bluetooth/Smartphone setting > Pairing registration",
    "Ricoh" to "Settings > Bluetooth > Pairing",
)

/**
 * Bluetooth location sync: pair once, then the phone's position is sent to the camera every
 * few seconds in the background, so each photo is tagged where it was taken.
 */
@Composable
private fun LocationScreen(status: LocationSyncStatus, actions: LocationActions) {
    Text(
        "Tags each photo with where it was taken and keeps the camera's clock set, by sending " +
            "the phone's location and time over Bluetooth.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (!status.paired) {
        InfoCard("Pair with the camera") {
            Step(1, "Put the camera in Bluetooth pairing mode (menu names vary by model):")
            for ((brand, how) in PAIRING_HOW_TO) {
                Text(
                    "$brand: $how",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Step(2, "Tap Pair camera and allow the permissions. Accept the Bluetooth pairing request if Android or the camera shows one.")
            Text(
                "This replaces the camera's pairing with its maker's app (SnapBridge, Creators' App, Camera Connect, XApp or GR World).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(
            onClick = actions.onPair,
            enabled = !status.pairing,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text(if (status.pairing) "Pairing…" else "Pair camera", style = MaterialTheme.typography.titleMedium)
        }
        status.message?.let { StatusMessage(it) }
        return
    }

    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Send location", style = MaterialTheme.typography.titleMedium)
                Text(
                    locationSummary(status),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = status.running, onCheckedChange = actions.onEnable)
        }
    }

    InfoCard("Status") {
        InfoRow("Camera", listOfNotNull(status.brand?.displayName, status.cameraName).joinToString(" ").ifEmpty { "Paired" })
        if (status.running) {
            InfoRow("Camera link", if (status.connected) "Connected" else "Searching")
            InfoRow(
                "Last sent",
                status.lastSentAtMillis?.let { t ->
                    DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(t)) +
                        (status.lastAccuracyMeters?.let { " (±${it.toInt()} m)" } ?: "")
                } ?: "–",
            )
        }
        status.message?.let { StatusMessage(it) }
    }

    InfoCard("Send every") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (s in LocationSync.INTERVAL_CHOICES) {
                FilterChip(
                    selected = status.intervalSeconds == s,
                    onClick = { actions.onInterval(s) },
                    label = { Text(intervalLabel(s)) },
                )
            }
        }
    }

    InfoCard("Background") {
        if (status.backgroundAllowed) {
            Text(
                "Keeps running in the background and starts sending as soon as the camera " +
                    "turns on, including after the phone restarts.",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Text(
                "Keeps running in the background while Send location is on. To have it start " +
                    "again by itself after the phone restarts, set this app's location " +
                    "permission to \"Allow all the time\".",
                style = MaterialTheme.typography.bodyMedium,
            )
            FilledTonalButton(onClick = actions.onAllowBackground, modifier = Modifier.fillMaxWidth()) {
                Text("Allow all the time")
            }
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = actions.onPair, enabled = !status.pairing, modifier = Modifier.weight(1f)) {
            Text(if (status.pairing) "Pairing…" else "Pair again")
        }
        OutlinedButton(onClick = actions.onForget, modifier = Modifier.weight(1f)) { Text("Forget camera") }
    }
}

@Composable
private fun StatusMessage(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun InfoCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, textAlign = TextAlign.End)
    }
}

