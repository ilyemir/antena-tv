package com.ilyemir.antena.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.ilyemir.antena.ui.theme.AntenaColors
import com.ilyemir.antena.ui.theme.AntenaType
import kotlinx.coroutines.delay

/**
 * Mi Box kumandasında rakam tuşu olmadığı için ekranda çıkan ince yatay 0-9 şeridi.
 * Sağ oka basınca açılır; Yukarı veya Geri ile kapanır.
 */
@Composable
fun NumPadBar(
    onDigit: (Char) -> Unit,
    onCommit: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(60)
        runCatching { firstFocus.requestFocus() }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(AntenaColors.OverlayScrim)
            .onPreviewKeyEvent { event ->
                if (event.key == Key.Back || event.key == Key.DirectionUp) {
                    if (event.type == KeyEventType.KeyUp) onDismiss()
                    true
                } else {
                    false
                }
            }
            .padding(horizontal = 24.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "KANAL NUMARASI GİR",
            style = AntenaType.Label,
            color = AntenaColors.TextSecondary,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ('0'..'9').forEachIndexed { index, digit ->
                DigitKey(
                    digit = digit,
                    modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                ) { onDigit(digit) }
            }
            Box(
                Modifier
                    .width(1.dp)
                    .height(40.dp)
                    .background(AntenaColors.Border),
            )
            PadAction("SİL", onClear)
            PadAction("TAMAM", onCommit)
        }
    }
}

@Composable
private fun DigitKey(digit: Char, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .size(width = 62.dp, height = 52.dp)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .background(AntenaColors.Surface)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) AntenaColors.Accent else AntenaColors.Border,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            digit.toString(),
            style = AntenaType.Digit,
            color = if (focused) AntenaColors.Accent else AntenaColors.TextPrimary,
        )
    }
}

@Composable
private fun PadAction(label: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .size(width = 92.dp, height = 52.dp)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .background(AntenaColors.Surface)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) AntenaColors.Accent else AntenaColors.Border,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = AntenaType.DigitAction,
            color = if (focused) AntenaColors.Accent else AntenaColors.TextSecondary,
        )
    }
}
