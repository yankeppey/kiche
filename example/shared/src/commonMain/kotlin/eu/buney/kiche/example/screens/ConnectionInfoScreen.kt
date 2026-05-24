package eu.buney.kiche.example.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.buney.kiche.example.Endpoints
import eu.buney.kiche.example.Http3DemoViewModel
import eu.buney.kiche.example.OpState
import eu.buney.kiche.example.ResultCard
import kotlinx.coroutines.launch

@Composable
fun ConnectionInfoScreen(vm: Http3DemoViewModel, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    Column(modifier = modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Performs the QUIC + TLS 1.3 handshake against ${Endpoints.HTTPBIN}/get and shows the " +
                "negotiated protocol — expect HTTP/3.0.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(
            onClick = { scope.launch { vm.loadConnectionInfo() } },
            enabled = vm.connectionInfo != OpState.Loading,
        ) {
            Text("Connect & fetch")
        }
        ResultCard(vm.connectionInfo, Modifier.weight(1f))
    }
}
