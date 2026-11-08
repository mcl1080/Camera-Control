package io.github.mcl1080.cameracontrol

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val viewModel: CameraViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    ConnectionTestScreen(
                        state = state,
                        onIpChange = viewModel::setCameraIp,
                        onConnect = viewModel::connect,
                        onDisconnect = viewModel::disconnect,
                        onTakePhoto = viewModel::takePhoto,
                    )
                }
            }
        }
    }
}

@Composable
fun ConnectionTestScreen(
    state: UiState,
    onIpChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onTakePhoto: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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

        val statusText = when (state.status) {
            Status.Disconnected -> "Not connected"
            Status.Connecting -> "Connecting…"
            Status.Connected -> "Connected to ${state.cameraModel}"
            Status.Busy -> "Taking photo…"
        }
        Text(statusText, style = MaterialTheme.typography.titleMedium)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.status == Status.Disconnected || state.status == Status.Connecting) {
                Button(onClick = onConnect, enabled = state.status == Status.Disconnected) { Text("Connect") }
            } else {
                Button(onClick = onTakePhoto, enabled = state.status == Status.Connected) { Text("Take photo") }
                OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
            }
        }

        Text("Log", style = MaterialTheme.typography.titleSmall)
        Card(Modifier.fillMaxWidth().weight(1f)) {
            val listState = rememberLazyListState()
            LaunchedEffect(state.log.size) {
                if (state.log.isNotEmpty()) listState.animateScrollToItem(state.log.lastIndex)
            }
            LazyColumn(state = listState, modifier = Modifier.padding(12.dp)) {
                items(state.log) { line ->
                    Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
