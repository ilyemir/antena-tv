package com.ilyemir.antena.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.ilyemir.antena.AntenaViewModel
import com.ilyemir.antena.ui.theme.AntenaColors
import com.ilyemir.antena.ui.theme.AntenaType
import kotlinx.coroutines.delay

/**
 * İlk kurulum: M3U/M3U8 listesi adresi girilir, kanallar içe aktarılır.
 */
@Composable
fun SetupScreen(viewModel: AntenaViewModel) {
    val importState by viewModel.importState.collectAsState()
    var url by remember { mutableStateOf(viewModel.m3uUrl.value) }
    val fieldFocus = remember { FocusRequester() }
    val buttonFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(100)
        runCatching { fieldFocus.requestFocus() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(AntenaColors.Background),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.width(620.dp)) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = AntenaColors.Accent)) { append("AN") }
                    withStyle(SpanStyle(color = AntenaColors.TextPrimary)) { append("TENA") }
                },
                style = AntenaType.Wordmark,
            )
            Text(
                "Yayın Konsolu",
                style = AntenaType.BodyMedium,
                color = AntenaColors.TextSecondary,
                modifier = Modifier.padding(top = 2.dp, bottom = 44.dp),
            )

            Text("M3U / M3U8 LİSTESİ", style = AntenaType.Label, color = AntenaColors.TextSecondary)
            Spacer(Modifier.height(8.dp))
            BasicTextField(
                value = url,
                onValueChange = { url = it },
                singleLine = true,
                textStyle = AntenaType.Field.copy(color = AntenaColors.TextPrimary),
                cursorBrush = SolidColor(AntenaColors.Accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { viewModel.importPlaylist(url) }),
                decorationBox = { innerTextField ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(AntenaColors.Surface)
                            .border(1.dp, AntenaColors.Border)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                    ) {
                        if (url.isEmpty()) {
                            Text(
                                "https://ornek.com/liste.m3u",
                                style = AntenaType.Field,
                                color = AntenaColors.TextSecondary,
                            )
                        }
                        innerTextField()
                    }
                },
                // BasicTextField, Aşağı tuşunu imleç için tüketir; odağı açıkça butona taşı.
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(fieldFocus)
                    .onPreviewKeyEvent { event ->
                        if (event.key == Key.DirectionDown && event.type == KeyEventType.KeyUp) {
                            runCatching { buttonFocus.requestFocus() }
                            true
                        } else {
                            false
                        }
                    },
            )

            Spacer(Modifier.height(22.dp))
            ConsoleButton(
                label = if (importState == AntenaViewModel.ImportState.Loading) "İNDİRİLİYOR" else "İÇE AKTAR",
                enabled = importState != AntenaViewModel.ImportState.Loading,
                modifier = Modifier
                    .focusRequester(buttonFocus)
                    .onPreviewKeyEvent { event ->
                        if (event.key == Key.DirectionUp && event.type == KeyEventType.KeyUp) {
                            runCatching { fieldFocus.requestFocus() }
                            true
                        } else {
                            false
                        }
                    },
            ) {
                viewModel.importPlaylist(url)
            }

            when (val state = importState) {
                is AntenaViewModel.ImportState.Error ->
                    Text(
                        state.message,
                        style = AntenaType.BodyMedium,
                        color = AntenaColors.Accent,
                        modifier = Modifier.padding(top = 18.dp),
                    )
                is AntenaViewModel.ImportState.Done ->
                    Text(
                        "${state.count} kanal içe aktarıldı",
                        style = AntenaType.BodyMedium,
                        color = AntenaColors.TextSecondary,
                        modifier = Modifier.padding(top = 18.dp),
                    )
                else -> Unit
            }

            Spacer(Modifier.height(48.dp))
            Text(
                "Liste adresini kumanda ile yazdıktan sonra İÇE AKTAR'a basın.",
                style = AntenaType.Label,
                color = AntenaColors.TextSecondary,
            )
        }
    }
}

/** Odakta amber dolguya dönen konsol düğmesi. */
@Composable
private fun ConsoleButton(
    label: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .onFocusChanged { focused = it.isFocused && enabled }
            .clickable(enabled = enabled, onClick = onClick)
            .background(if (focused) AntenaColors.Accent else AntenaColors.Surface)
            .border(1.dp, if (focused) AntenaColors.Accent else AntenaColors.Border)
            .padding(horizontal = 30.dp, vertical = 12.dp),
    ) {
        Text(
            label,
            style = AntenaType.BodyMedium,
            color = if (focused) AntenaColors.Background else AntenaColors.TextPrimary,
        )
    }
}
