package com.spacegoose.banditbox.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

class Choice(val label: String, val onPick: () -> Unit)

/** A titled list of buttons. Focus starts on the first one; Back closes it. */
@Composable
fun ChoiceDialog(title: String, choices: List<Choice>, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), modifier = Modifier.width(320.dp)) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                choices.forEachIndexed { i, choice ->
                    Button(
                        onClick = choice.onPick,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                    ) {
                        Text(choice.label)
                    }
                }
            }
        }
    }
    LaunchedEffect(title, choices.size) { if (choices.isNotEmpty()) first.requestFocus() }
}
