package io.github.mcl1080.cameracontrol

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mcl1080.cameracontrol.ptpip.ExposureSetting
import io.github.mcl1080.cameracontrol.ptpip.ExposureState
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
                    Column(
                        Modifier
                            .fillMaxSize()
                            .safeDrawingPadding()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (state.status == Status.Disconnected || state.status == Status.Connecting) {
                            ConnectScreen(state, viewModel::setCameraIp, viewModel::connect)
                        } else {
                            RemoteScreen(
                                state = state,
                                frame = frame,
                                onLiveViewChange = viewModel::setLiveView,
                                onTakePhoto = viewModel::takePhoto,
                                onSetExposure = viewModel::setExposure,
                                onDisconnect = viewModel::disconnect,
                            )
                        }
                        LogPanel(state.log, Modifier.weight(1f))
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

@Composable
private fun RemoteScreen(
    state: UiState,
    frame: ImageBitmap?,
    onLiveViewChange: (Boolean) -> Unit,
    onTakePhoto: () -> Unit,
    onSetExposure: (SettingState, Long) -> Unit,
    onDisconnect: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(state.cameraModel ?: "Camera", style = MaterialTheme.typography.titleMedium)
            state.exposure?.let {
                Text("Mode ${it.mode.label}", style = MaterialTheme.typography.bodyMedium)
            }
        }
        TextButton(onClick = onDisconnect) { Text("Disconnect") }
    }

    LiveView(frame, state.liveViewOn)

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Live view", Modifier.weight(1f))
        Switch(checked = state.liveViewOn, onCheckedChange = onLiveViewChange)
    }

    state.exposure?.let { ExposureControls(it, enabled = state.status == Status.Connected, onSetExposure) }

    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Button(
            onClick = onTakePhoto,
            enabled = state.status == Status.Connected,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F), contentColor = Color.White),
            modifier = Modifier.size(84.dp),
        ) {
            Text(if (state.status == Status.Busy) "…" else "Shoot", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun LiveView(frame: ImageBitmap?, on: Boolean) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(3f / 2f)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        if (frame != null && on) {
            Image(frame, contentDescription = "Live view", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        } else {
            Text(if (on) "Starting live view…" else "Live view off", color = Color.White)
        }
    }
}

private val settingOrder = listOf(
    ExposureSetting.SHUTTER_SPEED to "Shutter",
    ExposureSetting.APERTURE to "Aperture",
    ExposureSetting.ISO to "ISO",
    ExposureSetting.EXPOSURE_COMPENSATION to "Exp. comp.",
)

@Composable
private fun ExposureControls(exposure: ExposureState, enabled: Boolean, onSet: (SettingState, Long) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        for ((setting, title) in settingOrder) {
            val state = exposure.settings[setting] ?: continue
            SettingControl(title, state, enabled, onSet, Modifier.weight(1f))
        }
    }
}

@Composable
private fun SettingControl(
    title: String,
    state: SettingState,
    enabled: Boolean,
    onSet: (SettingState, Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val canEdit = enabled && state.editable && state.choices.isNotEmpty()
    Box(modifier) {
        OutlinedCard(
            onClick = { open = true },
            enabled = canEdit,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                Text(title, style = MaterialTheme.typography.labelSmall)
                Text(
                    if (state.setting == ExposureSetting.ISO) state.label.removePrefix("ISO ") else state.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (state.editable) FontWeight.Bold else FontWeight.Normal,
                )
                if (!state.editable) Text("auto", style = MaterialTheme.typography.labelSmall)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (value in state.choices) {
                DropdownMenuItem(
                    text = {
                        Text(
                            state.labelFor(value),
                            fontWeight = if (value == state.value) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                    onClick = {
                        open = false
                        if (value != state.value) onSet(state, value)
                    },
                )
            }
        }
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
            Card(Modifier.fillMaxWidth().weight(1f)) {
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
