package com.spacegoose.banditbox.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.spacegoose.banditbox.R
import com.spacegoose.banditbox.layout.PinHash

private val DIGIT_KEYS = mapOf(
    Key.Zero to '0', Key.One to '1', Key.Two to '2', Key.Three to '3', Key.Four to '4',
    Key.Five to '5', Key.Six to '6', Key.Seven to '7', Key.Eight to '8', Key.Nine to '9',
    Key.NumPad0 to '0', Key.NumPad1 to '1', Key.NumPad2 to '2', Key.NumPad3 to '3', Key.NumPad4 to '4',
    Key.NumPad5 to '5', Key.NumPad6 to '6', Key.NumPad7 to '7', Key.NumPad8 to '8', Key.NumPad9 to '9',
)

/**
 * A 4-digit PIN pad. Works with the on-screen buttons or the remote's number keys.
 * [onPin] gets each complete PIN and returns false to reject it (the pad clears and says so).
 */
@Composable
fun PinDialog(title: String, onPin: (String) -> Boolean, onDismiss: () -> Unit) {
    var entered by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    val firstKey = remember { FocusRequester() }

    fun type(digit: Char) {
        if (entered.length >= PinHash.PIN_LENGTH) return
        wrong = false
        entered += digit
        if (entered.length == PinHash.PIN_LENGTH) {
            if (!onPin(entered)) wrong = true
            entered = ""
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .width(360.dp)
                .onPreviewKeyEvent { event ->
                    val digit = DIGIT_KEYS[event.key] ?: return@onPreviewKeyEvent false
                    if (event.type == KeyEventType.KeyUp) type(digit)
                    true
                },
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(text = title, style = MaterialTheme.typography.titleLarge)
                Text(
                    text = "●".repeat(entered.length) + "○".repeat(PinHash.PIN_LENGTH - entered.length),
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    text = if (wrong) stringResource(R.string.pin_wrong) else " ",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                listOf("123", "456", "789").forEachIndexed { rowIndex, digits ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        digits.forEachIndexed { i, d ->
                            val focus = if (rowIndex == 0 && i == 0) Modifier.focusRequester(firstKey) else Modifier
                            Button(onClick = { type(d) }, modifier = focus.size(72.dp, 48.dp)) { Text(d.toString()) }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { entered = entered.dropLast(1) }, modifier = Modifier.size(72.dp, 48.dp)) { Text("⌫") }
                    Button(onClick = { type('0') }, modifier = Modifier.size(72.dp, 48.dp)) { Text("0") }
                    Button(onClick = onDismiss, modifier = Modifier.size(72.dp, 48.dp)) { Text("✕") }
                }
            }
        }
    }
    LaunchedEffect(Unit) { firstKey.requestFocus() }
}
