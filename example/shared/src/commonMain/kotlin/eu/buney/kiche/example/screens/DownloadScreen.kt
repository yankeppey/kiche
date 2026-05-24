package eu.buney.kiche.example.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.buney.kiche.example.Http3DemoViewModel
import eu.buney.kiche.example.OpState
import eu.buney.kiche.example.ResultCard
import kotlinx.coroutines.launch

private val sizes = listOf(1024 to "1 KB", 16 * 1024 to "16 KB", 100 * 1024 to "100 KB")

@Composable
fun DownloadScreen(vm: Http3DemoViewModel, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    Column(modifier = modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "GET /stream-bytes/{n} downloads n random bytes, streamed in chunks.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            sizes.forEach { (n, label) ->
                Button(
                    onClick = { scope.launch { vm.runDownload(n) } },
                    enabled = vm.download != OpState.Loading,
                ) {
                    Text(label)
                }
            }
        }
        ResultCard(vm.download, Modifier.weight(1f))
    }
}
