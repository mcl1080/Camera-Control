package io.github.mcl1080.cameracontrol

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import io.github.mcl1080.cameracontrol.ptpip.StorageInfo
import io.github.mcl1080.cameracontrol.ptpip.ExposureState
import io.github.mcl1080.cameracontrol.ptpip.LiveViewInfo
import io.github.mcl1080.cameracontrol.ptpip.SettingState

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
                    Column(
                        Modifier
                            .fillMaxSize()
                            .safeDrawingPadding()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        val connected = state.status != Status.Disconnected && state.status != Status.Connecting
                        BackHandler(enabled = connected && state.screen == Screen.Remote) { viewModel.goHome() }
                        Column(
                            Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            if (!connected) {
                                ConnectScreen(state, viewModel::setCameraIp, viewModel::connect)
                            } else if (state.screen == Screen.Home) {
                                HomeScreen(
                                    state,
                                    onOpenRemote = viewModel::openRemote,
                                    onSyncClock = viewModel::syncClock,
                                    onAutoSyncClockChange = viewModel::setAutoSyncClock,
                                    onDisconnect = viewModel::disconnect,
                                )
                            } else {
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
                        }
                        LogPanel(state.log)
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectScreen(state: UiState, onIpChange: (String) -> Unit, onConnect: () -> Unit) {
    Text("Camera Control", style = MaterialTheme.typography.headlineSmall)
    Text(
        "On the Z5, open Setup menu > Connect to smart device > Wi-Fi connection > Establish Wi-Fi connection. " +
            "Join the camera's Wi-Fi network from your phone's settings, then tap Connect.",
        style = MaterialTheme.typography.bodyMedium,
    )
    OutlinedTextField(
        value = state.cameraIp,
        onValueChange = onIpChange,
        label = { Text("Camera IP address") },
        singleLine = true,
        enabled = state.status == Status.Disconnected,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = onConnect, enabled = state.status == Status.Disconnected) {
        Text(if (state.status == Status.Connecting) "Connecting…" else "Connect")
    }
}

/** The landing page once connected: what the camera is and how it's doing, and the way to the remote. */
@Composable
private fun HomeScreen(
    state: UiState,
    onOpenRemote: () -> Unit,
    onSyncClock: () -> Unit,
    onAutoSyncClockChange: (Boolean) -> Unit,
    onDisconnect: () -> Unit,
) {
    val info = state.deviceInfo
    val status = state.cameraStatus
    val exposure = state.exposure

    Text(state.cameraModel ?: "Camera", style = MaterialTheme.typography.headlineSmall)
    Text("Connected over Wi-Fi", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)

    InfoCard("Camera") {
        InfoRow("Model", info?.model ?: "–")
        InfoRow("Firmware", info?.deviceVersion?.ifBlank { null } ?: "–")
        InfoRow("Serial number", info?.serialNumber?.trim()?.ifBlank { null } ?: "–")
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

    Button(onClick = onOpenRemote, modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Text("Remote & live view", style = MaterialTheme.typography.titleMedium)
    }
    InfoCard("Time & location") {
        InfoRow("Camera clock", clockSummary(state.clockOffsetSeconds))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Sync clock on connect", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(checked = state.autoSyncClock, onCheckedChange = onAutoSyncClockChange)
        }
        FilledTonalButton(onClick = onSyncClock, enabled = state.status == Status.Connected, modifier = Modifier.fillMaxWidth()) {
            Text("Sync clock now")
        }
        InfoRow("Location", "coming soon (Bluetooth)")
    }
    TextButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) { Text("Disconnect") }
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
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
