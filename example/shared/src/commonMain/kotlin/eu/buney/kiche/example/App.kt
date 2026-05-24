package eu.buney.kiche.example

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.buney.kiche.example.screens.ConnectionInfoScreen
import eu.buney.kiche.example.screens.DownloadScreen
import eu.buney.kiche.example.screens.EchoScreen
import eu.buney.kiche.example.screens.StreamingScreen

/** Each demo is one screen reachable from the main menu. */
enum class DemoScreen(val title: String, val description: String) {
    ConnectionInfo(
        title = "Connection & protocol",
        description = "Handshake a QUIC connection and confirm the negotiated protocol is HTTP/3.",
    ),
    Echo(
        title = "Echo (POST body)",
        description = "POST a request body and read it back from the response.",
    ),
    Download(
        title = "Download bytes",
        description = "GET a fixed number of bytes and measure the transfer.",
    ),
    Streaming(
        title = "Streamed response",
        description = "Read a chunked, newline-delimited streaming response.",
    ),
}

/**
 * Root composable. Menu-of-buttons → feature-screen UX with hand-rolled `when` navigation
 * (no nav library), mirroring the Compose Multiplatform sample. `null` screen = the menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    MaterialTheme {
        val viewModel = remember { Http3DemoViewModel() }
        DisposableEffect(Unit) {
            onDispose { viewModel.close() }
        }

        var current by remember { mutableStateOf<DemoScreen?>(null) }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(current?.title ?: "Kiche — HTTP/3 demo") },
                    navigationIcon = {
                        if (current != null) {
                            IconButton(onClick = { current = null }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        }
                    },
                )
            },
        ) { padding ->
            val content = Modifier.fillMaxSize().padding(padding)
            when (current) {
                null -> DemoMenu(onSelect = { current = it }, modifier = content)
                DemoScreen.ConnectionInfo -> ConnectionInfoScreen(viewModel, content)
                DemoScreen.Echo -> EchoScreen(viewModel, content)
                DemoScreen.Download -> DownloadScreen(viewModel, content)
                DemoScreen.Streaming -> StreamingScreen(viewModel, content)
            }
        }
    }
}

@Composable
private fun DemoMenu(onSelect: (DemoScreen) -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(modifier = modifier.padding(16.dp)) {
        items(DemoScreen.entries) { demo ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .clickable { onSelect(demo) },
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(demo.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text(demo.description, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
