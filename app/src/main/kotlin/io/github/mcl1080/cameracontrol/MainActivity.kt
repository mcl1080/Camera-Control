package io.github.mcl1080.cameracontrol

import android.os.Bundle
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mcl1080.cameracontrol.ptpip.ExposureSetting
import io.github.mcl1080.cameracontrol.location.LocationSync
import io.github.mcl1080.cameracontrol.location.LocationSyncStatus
import io.github.mcl1080.cameracontrol.ptpip.StorageInfo
import androidx.lifecycle.compose.LifecycleResumeEffect
import java.text.DateFormat
import java.util.Date
import io.github.mcl1080.cameracontrol.ptpip.ExposureState
import io.github.mcl1080.cameracontrol.ptpip.LiveViewInfo
import io.github.mcl1080.cameracontrol.ptpip.SettingState

/** The app's pages. The remote itself opens from the Wi-Fi page once connected. */
private enum class Page { Home, Wifi, Location }

class MainActivity : ComponentActivity() {
    private val viewModel: CameraViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Keep the screen on while using the remote.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    val frame by viewModel.frame.collectAsStateWithLifecycle()
                    val fps by viewModel.fps.collectAsStateWithLifecycle()
                    val liveViewInfo by viewModel.liveViewInfo.collectAsStateWithLifecycle()
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

                    var page by rememberSaveable { mutableStateOf(Page.Home) }
                    val connected = state.status != Status.Disconnected && state.status != Status.Connecting
                    val inRemote = connected && state.screen == Screen.Remote
                    BackHandler(enabled = !inRemote && page != Page.Home) { page = Page.Home }
                    BackHandler(enabled = inRemote) { viewModel.goHome() }

                    Column(
                        Modifier
                            .fillMaxSize()
                            .safeDrawingPadding()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        when {
                            inRemote -> {
                                PageColumn(Modifier.weight(1f)) {
                                    RemoteScreen(
                                        state = state,
                                        frame = frame,
                                        fps = fps,
                                        liveViewInfo = liveViewInfo,
                                        onTapToFocus = viewModel::tapToFocus,
                                        onFocus = viewModel::focus,
                                        onLiveViewChange = viewModel::setLiveView,
                                        onTakePhoto = viewModel::takePhoto,
                                        onSetExposure = viewModel::setExposure,
                                        onStepExposure = viewModel::stepExposure,
                                        onAfOnShootChange = viewModel::setAfOnShoot,
                                        onBack = viewModel::goHome,
                                    )
                                }
                                LogPanel(state.log)
                            }
                            page == Page.Wifi -> {
                                PageHeader("Wi-Fi remote") { page = Page.Home }
                                PageColumn(Modifier.weight(1f)) {
                                    if (!connected) {
                                        ConnectScreen(state, viewModel::setCameraIp, viewModel::connect)
                                    } else {
                                        CameraScreen(
                                            state,
                                            onOpenRemote = viewModel::openRemote,
                                            onSyncClock = viewModel::syncClock,
                                            onAutoSyncClockChange = viewModel::setAutoSyncClock,
                                            onDisconnect = viewModel::disconnect,
                                        )
                                    }
                                }
                                LogPanel(state.log)
                            }
                            page == Page.Location -> {
                                PageHeader("Location sync") { page = Page.Home }
                                PageColumn(Modifier.weight(1f)) {
                                    LocationScreen(locationSync, locationActions)
                                }
                            }
                            else -> PageColumn(Modifier.weight(1f)) {
                                HomePage(
                                    state = state,
                                    location = locationSync,
                                    onOpenWifi = { page = Page.Wifi },
                                    onOpenLocation = { page = Page.Location },
                                    onOpenRemote = {
                                        page = Page.Wifi
                                        viewModel.openRemote()
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A page's scrolling body. */
@Composable
private fun PageColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/** Back arrow and title at the top of a page opened from the home page. */
@Composable
private fun PageHeader(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Spacer(Modifier.size(4.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}

/** Whether a feature is working, waiting on something, or off: shown as a coloured dot. */
private enum class Indicator { On, Waiting, Off }

@Composable
private fun Indicator.color(): Color = when (this) {
    Indicator.On -> Color(0xFF4CAF50)
    Indicator.Waiting -> Color(0xFFFFB300)
    Indicator.Off -> MaterialTheme.colorScheme.outline
}

/** The landing page: one card per feature, each showing its status and opening its page. */
@Composable
private fun HomePage(
    state: UiState,
    location: LocationSyncStatus,
    onOpenWifi: () -> Unit,
    onOpenLocation: () -> Unit,
    onOpenRemote: () -> Unit,
) {
    val connected = state.status != Status.Disconnected && state.status != Status.Connecting
    Spacer(Modifier.height(24.dp))
    Text(
        stringResource(R.string.app_name),
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.SemiBold,
    )
    Text(
        "Remote control, clock and location for your camera",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))

    NavCard(
        icon = painterResource(R.drawable.ic_wifi),
        title = "Wi-Fi remote",
        subtitle = wifiSummary(state),
        indicator = when {
            connected -> Indicator.On
            state.status == Status.Connecting -> Indicator.Waiting
            else -> Indicator.Off
        },
        onClick = onOpenWifi,
    )
    NavCard(
        icon = painterResource(R.drawable.ic_bluetooth),
        title = "Location sync",
        subtitle = locationSummary(location),
        indicator = when {
            location.running && location.connected -> Indicator.On
            location.running || location.pairing -> Indicator.Waiting
            else -> Indicator.Off
        },
        onClick = onOpenLocation,
    )
    if (connected) {
        Spacer(Modifier.height(4.dp))
        Button(onClick = onOpenRemote, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text("Open remote & live view", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun NavCard(icon: Painter, title: String, subtitle: String, indicator: Indicator, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.size(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(indicator.color()),
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun wifiSummary(state: UiState): String = when (state.status) {
    Status.Disconnected -> "Not connected"
    Status.Connecting -> "Connecting…"
    else -> listOfNotNull(
        "Connected to ${state.cameraModel ?: "camera"}",
        state.cameraStatus?.batteryPercent?.let { "battery $it%" },
    ).joinToString(" · ")
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

@Composable
private fun ConnectScreen(state: UiState, onIpChange: (String) -> Unit, onConnect: () -> Unit) {
    val context = LocalContext.current
    InfoCard("Connect to the camera") {
        Step(1, "On the camera, open Setup menu > Connect to smart device > Wi-Fi connection > Establish Wi-Fi connection.")
        Step(2, "Join the camera's Wi-Fi network in your phone's settings. If Android warns there's no internet, stay connected.")
        Step(3, "Come back here and tap Connect.")
        TextButton(
            onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) } },
            contentPadding = PaddingValues(horizontal = 0.dp),
        ) { Text("Open Wi-Fi settings") }
    }
    OutlinedTextField(
        value = state.cameraIp,
        onValueChange = onIpChange,
        label = { Text("Camera IP address") },
        singleLine = true,
        enabled = state.status == Status.Disconnected,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = onConnect,
        enabled = state.status == Status.Disconnected,
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) {
        Text(if (state.status == Status.Connecting) "Connecting…" else "Connect", style = MaterialTheme.typography.titleMedium)
    }
}

/** The Wi-Fi page once connected: what the camera is and how it's doing, and the way to the remote. */
@Composable
private fun CameraScreen(
    state: UiState,
    onOpenRemote: () -> Unit,
    onSyncClock: () -> Unit,
    onAutoSyncClockChange: (Boolean) -> Unit,
    onDisconnect: () -> Unit,
) {
    val info = state.deviceInfo
    val status = state.cameraStatus
    val exposure = state.exposure

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(state.cameraModel ?: "Camera", style = MaterialTheme.typography.headlineSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(Indicator.On.color()))
            Spacer(Modifier.size(6.dp))
            Text("Connected over Wi-Fi", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    Button(onClick = onOpenRemote, modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Text("Remote & live view", style = MaterialTheme.typography.titleMedium)
    }

    InfoCard("Status") {
        InfoRow("Battery", status?.batteryPercent?.let { "$it%" } ?: "–")
        InfoRow("Mode", exposure?.mode?.label ?: "–")
        InfoRow("Lens", status?.focalLengthMm?.let { "%.0f mm".format(it) } ?: "–")
        if (exposure != null) {
            val parts = listOf(ExposureSetting.SHUTTER_SPEED, ExposureSetting.APERTURE, ExposureSetting.ISO)
                .mapNotNull { exposure.settings[it]?.label }
            InfoRow("Exposure", parts.joinToString("  ·  ").ifBlank { "–" })
        }
    }

    InfoCard("Memory cards") {
        val cards = status?.cards
        when {
            cards == null -> InfoRow("Reading…", "")
            cards.isEmpty() -> InfoRow("No card", "")
            else -> cards.forEach { InfoRow("Slot ${it.slot}", cardSummary(it)) }
        }
    }

    InfoCard("Time") {
        InfoRow("Camera clock", clockSummary(state.clockOffsetSeconds))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Sync clock on connect", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(checked = state.autoSyncClock, onCheckedChange = onAutoSyncClockChange)
        }
        FilledTonalButton(onClick = onSyncClock, enabled = state.status == Status.Connected, modifier = Modifier.fillMaxWidth()) {
            Text("Sync clock now")
        }
    }

    InfoCard("Camera") {
        InfoRow("Model", info?.model ?: "–")
        InfoRow("Firmware", info?.deviceVersion?.ifBlank { null } ?: "–")
        InfoRow("Serial number", info?.serialNumber?.trim()?.ifBlank { null } ?: "–")
    }

    OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) { Text("Disconnect") }
}

class LocationActions(
    val onPair: () -> Unit,
    val onEnable: (Boolean) -> Unit,
    val onInterval: (Int) -> Unit,
    val onForget: () -> Unit,
    val onAllowBackground: () -> Unit,
)

/**
 * Bluetooth location sync: pair once, then the phone's position is sent to the camera every
 * few seconds in the background, so each photo is tagged where it was taken. Works without
 * the Wi-Fi remote being connected.
 */
@Composable
private fun LocationScreen(status: LocationSyncStatus, actions: LocationActions) {
    Text(
        "Tags each photo with where it was taken and keeps the camera's clock set, by sending " +
            "the phone's location and time over Bluetooth. Works without the Wi-Fi remote connected.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (!status.paired) {
        InfoCard("Pair with the camera") {
            Step(1, "On the camera, open Setup menu > Connect to smart device > Pairing (Bluetooth) > Start pairing.")
            Step(2, "Tap Pair camera and allow the permissions. Accept the Bluetooth pairing request if Android shows one.")
            Text(
                "This replaces the camera's pairing with SnapBridge.",
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
        InfoRow("Camera", status.cameraName ?: "Paired")
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

/** "In sync", or how far the camera's clock is from the phone's. */
private fun clockSummary(offsetSeconds: Long?): String {
    if (offsetSeconds == null) return "–"
    val abs = kotlin.math.abs(offsetSeconds)
    if (abs <= 2) return "In sync with phone"
    val amount = when {
        abs < 60 -> "$abs s"
        abs < 3600 -> "${abs / 60} min ${abs % 60} s"
        abs < 86_400 -> "${abs / 3600} h ${abs % 3600 / 60} min"
        else -> "${abs / 86_400} days"
    }
    return if (offsetSeconds < 0) "$amount behind" else "$amount ahead"
}

private fun cardSummary(card: StorageInfo): String {
    val gb = 1_000_000_000.0
    val space = "%.1f of %.0f GB free".format(card.freeBytes / gb, card.maxCapacityBytes / gb)
    return card.freeImages?.let { "%,d shots left · %s".format(it, space) } ?: space
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

@Composable
private fun RemoteScreen(
    state: UiState,
    frame: ImageBitmap?,
    fps: Int,
    liveViewInfo: LiveViewInfo?,
    onTapToFocus: (Float, Float) -> Unit,
    onFocus: () -> Unit,
    onLiveViewChange: (Boolean) -> Unit,
    onTakePhoto: () -> Unit,
    onSetExposure: (SettingState, Long) -> Unit,
    onStepExposure: (ExposureSetting, Int) -> Unit,
    onAfOnShootChange: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("‹ Camera") }
        Text(
            "Remote" + (state.exposure?.let { " · Mode ${it.mode.label}" } ?: ""),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
        )
    }

    LiveView(
        frame = frame,
        on = state.liveViewOn,
        fps = fps,
        info = liveViewInfo,
        focus = state.focus,
        canFocus = state.status == Status.Connected,
        onTap = onTapToFocus,
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Live view", Modifier.weight(1f))
        Switch(checked = state.liveViewOn, onCheckedChange = onLiveViewChange)
        Spacer(Modifier.size(16.dp))
        Text("Autofocus on shoot", Modifier.weight(1f), textAlign = TextAlign.End)
        Spacer(Modifier.size(8.dp))
        Switch(checked = state.afOnShoot, onCheckedChange = onAfOnShootChange)
    }

    state.exposure?.let {
        ExposureControls(it, enabled = state.status == Status.Connected, onSetExposure, onStepExposure)
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalButton(
            onClick = onFocus,
            enabled = state.status == Status.Connected,
            shape = CircleShape,
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(64.dp),
        ) {
            Text(
                if (state.status == Status.Focusing) "…" else "Focus",
                maxLines = 1,
                softWrap = false,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        Button(
            onClick = onTakePhoto,
            enabled = state.status == Status.Connected,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F), contentColor = Color.White),
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(84.dp),
        ) {
            Text(
                if (state.status == Status.Busy) "…" else "Shoot",
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
            )
        }
        // Balances the Focus button so Shoot stays centred.
        Box(Modifier.size(64.dp))
    }
    Text(
        if (state.afOnShoot) {
            "Tap the picture to focus there. Shoot focuses first."
        } else {
            "Tap the picture to focus there. Shoot keeps the current focus."
        },
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
}

/** Where an image of [imageSize] is drawn inside [box] with ContentScale.Fit. */
private fun fitRect(box: Size, imageSize: Size): Rect {
    if (imageSize.width <= 0f || imageSize.height <= 0f) return Rect(Offset.Zero, box)
    val scale = minOf(box.width / imageSize.width, box.height / imageSize.height)
    val w = imageSize.width * scale
    val h = imageSize.height * scale
    return Rect(Offset((box.width - w) / 2, (box.height - h) / 2), Size(w, h))
}

@Composable
private fun LiveView(
    frame: ImageBitmap?,
    on: Boolean,
    fps: Int,
    info: LiveViewInfo?,
    focus: FocusMarker?,
    canFocus: Boolean,
    onTap: (Float, Float) -> Unit,
) {
    val imageSize = frame?.let { Size(it.width.toFloat(), it.height.toFloat()) } ?: Size.Zero
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(3f / 2f)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black)
            .pointerInput(imageSize, canFocus) {
                detectTapGestures { tap ->
                    if (!canFocus || imageSize == Size.Zero) return@detectTapGestures
                    val r = fitRect(Size(size.width.toFloat(), size.height.toFloat()), imageSize)
                    if (r.contains(tap)) onTap((tap.x - r.left) / r.width, (tap.y - r.top) / r.height)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (frame != null && on) {
            Image(frame, contentDescription = "Live view", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            FocusOverlay(imageSize, info, focus)
            Text(
                "$fps fps",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .background(Color(0x80000000), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        } else {
            Text(if (on) "Starting live view…" else "Live view off", color = Color.White)
        }
    }
}

/**
 * Draws the camera's focus area (thin white box, from the live view header) and, after a
 * focus attempt, a marker that turns green when focused or red when the camera couldn't focus.
 */
@Composable
private fun FocusOverlay(imageSize: Size, info: LiveViewInfo?, focus: FocusMarker?) {
    Canvas(Modifier.fillMaxSize()) {
        val r = fitRect(size, imageSize)
        if (info != null && info.afWidth > 0 && info.afHeight > 0) {
            val w = info.afWidth.toFloat() / info.wholeWidth * r.width
            val h = info.afHeight.toFloat() / info.wholeHeight * r.height
            val cx = r.left + info.afCenterX.toFloat() / info.wholeWidth * r.width
            val cy = r.top + info.afCenterY.toFloat() / info.wholeHeight * r.height
            drawRect(Color.White.copy(alpha = 0.7f), Offset(cx - w / 2, cy - h / 2), Size(w, h), style = Stroke(2f))
        }
        if (focus != null) {
            val color = when (focus.result) {
                FocusResult.Focusing -> Color.White
                FocusResult.Focused -> Color(0xFF4CAF50)
                FocusResult.Failed -> Color(0xFFF44336)
            }
            val side = 36.dp.toPx()
            val c = Offset(r.left + focus.x * r.width, r.top + focus.y * r.height)
            drawRect(color, Offset(c.x - side / 2, c.y - side / 2), Size(side, side), style = Stroke(3.dp.toPx()))
        }
    }
}

private val settingOrder = listOf(
    ExposureSetting.SHUTTER_SPEED to "Shutter",
    ExposureSetting.APERTURE to "Aperture",
    ExposureSetting.ISO to "ISO",
    ExposureSetting.EXPOSURE_COMPENSATION to "Exp. comp.",
)

/**
 * A row of the four settings. Tap one to select it, then turn it like a command dial:
 * the ‹ › buttons step one value at a time, and the strip below shows the neighbouring
 * values so you can tap one directly.
 */
@Composable
private fun ExposureControls(
    exposure: ExposureState,
    enabled: Boolean,
    onSet: (SettingState, Long) -> Unit,
    onStep: (ExposureSetting, Int) -> Unit,
) {
    var selected by remember { mutableStateOf<ExposureSetting?>(null) }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        for ((setting, title) in settingOrder) {
            val state = exposure.settings[setting] ?: continue
            SettingCard(
                title = title,
                state = state,
                selected = selected == setting,
                onClick = { selected = if (selected == setting) null else setting },
                modifier = Modifier.weight(1f),
            )
        }
    }

    val current = selected?.let { exposure.settings[it] }
    if (current != null) {
        if (current.editable && current.choices.isNotEmpty()) {
            SettingDial(current, enabled, onSet, onStep)
        } else {
            Text(
                "Set by the camera in ${exposure.mode.label} mode",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun SettingCard(
    title: String,
    state: SettingState,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(
        onClick = onClick,
        modifier = modifier,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else CardDefaults.outlinedCardBorder(),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            Text(
                if (state.setting == ExposureSetting.ISO) state.label.removePrefix("ISO ") else state.label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (state.editable) FontWeight.Bold else FontWeight.Normal,
                color = if (state.editable) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
            )
            Text(if (state.editable) " " else "auto", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun SettingDial(
    state: SettingState,
    enabled: Boolean,
    onSet: (SettingState, Long) -> Unit,
    onStep: (ExposureSetting, Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        FilledTonalButton(
            onClick = { onStep(state.setting, -1) },
            enabled = enabled && state.index > 0,
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(44.dp),
        ) { Text("‹", style = MaterialTheme.typography.titleLarge) }

        val listState = rememberLazyListState()
        val index = state.index
        // Keep the current value in the middle of the strip as it changes.
        LaunchedEffect(state.setting, index) {
            if (index >= 0) {
                val visible = listState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(3)
                listState.animateScrollToItem((index - visible / 2).coerceAtLeast(0))
            }
        }
        LazyRow(
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(horizontal = 8.dp),
            modifier = Modifier.weight(1f),
        ) {
            itemsIndexed(state.choices) { i, value ->
                FilterChip(
                    selected = i == index,
                    onClick = { if (i != index) onSet(state, value) },
                    enabled = enabled,
                    label = { Text(state.labelFor(value), maxLines = 1, softWrap = false) },
                )
            }
        }

        FilledTonalButton(
            onClick = { onStep(state.setting, 1) },
            enabled = enabled && index < state.choices.lastIndex,
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(44.dp),
        ) { Text("›", style = MaterialTheme.typography.titleLarge) }
    }
}

@Composable
private fun LogPanel(log: List<String>, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Log", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide" else "Show") }
        }
        if (expanded) {
            Card(Modifier.fillMaxWidth().height(200.dp)) {
                val listState = rememberLazyListState()
                LaunchedEffect(log.size) {
                    if (log.isNotEmpty()) listState.animateScrollToItem(log.lastIndex)
                }
                LazyColumn(state = listState, modifier = Modifier.padding(12.dp)) {
                    items(log) { line ->
                        Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        } else if (log.isNotEmpty()) {
            Text(log.last(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
}
