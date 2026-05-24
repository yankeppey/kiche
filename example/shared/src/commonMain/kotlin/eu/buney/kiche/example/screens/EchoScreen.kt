package eu.buney.kiche.example.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.buney.kiche.example.Http3DemoViewModel
import eu.buney.kiche.example.OpState
import eu.buney.kiche.example.ResultCard
import kotlinx.coroutines.launch

@Composable
fun EchoScreen(vm: Http3DemoViewModel, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var body by remember { mutableStateOf("Hello over HTTP/3!") }
    Column(modifier = modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "POSTs the text below to /post; the server echoes it back inside the response JSON " +
                "(see the \"data\" field).",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = body,
            onValueChange = { body = it },
            label = { Text("Body to echo") },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { scope.launch { vm.runEcho(body) } },
            enabled = vm.echo != OpState.Loading,
        ) {
            Text("Send POST")
        }
        ResultCard(vm.echo, Modifier.weight(1f))
    }
}
