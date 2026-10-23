package com.arcsus.arcdisney.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arcsus.arcdisney.ui.theme.Night

/** Asked once, the first time the update button runs without a TMDB key built in. */
@Composable
fun TmdbKeyDialog(onSave: (String) -> Unit, onSkip: () -> Unit, onDismiss: () -> Unit) {
    var key by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Find new Disney films") },
        text = {
            Column {
                Text(
                    "New films come from TMDB. Paste a free API key from themoviedb.org " +
                        "(Settings → API) to turn this on. Without one, update only re-checks " +
                        "films that had no releases.",
                    color = Night.Muted,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it.trim() },
                    singleLine = true,
                    label = { Text("TMDB API key") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(key) }, enabled = key.length >= 16) { Text("Save & update") }
        },
        dismissButton = { TextButton(onClick = onSkip) { Text("Just re-check") } },
    )
}
