package eu.buney.kiche.example

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Renders an [OpState] for any demo screen: idle / spinner / monospace result / error. */
@Composable
fun ResultCard(state: OpState, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Box(modifier = Modifier.padding(16.dp)) {
            when (state) {
                OpState.Idle ->
                    Text("No request sent yet.", style = MaterialTheme.typography.bodyMedium)

                OpState.Loading ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("  Loading…", style = MaterialTheme.typography.bodyMedium)
                    }

                is OpState.Success ->
                    Text(
                        text = state.text,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    )

                is OpState.Failure ->
                    Text(
                        text = "Error: ${state.message}",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    )
            }
        }
    }
}
