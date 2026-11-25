package com.ilyemir.antena.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.ilyemir.antena.data.db.ChannelEntity
import com.ilyemir.antena.ui.theme.AntenaColors
import com.ilyemir.antena.ui.theme.AntenaType
import kotlinx.coroutines.delay

/**
 * Büyük puntolu sol kanal çekmecesi: "Özel Liste" (zap halkası) ve "Tüm Kanallar"
 * sekmeleri arasında geçiş yapılır. Sol tuş veya Geri ile kapanır.
 * onSelect, dışarıdaki tam kanal listesindeki indeksi verir.
 */
@Composable
fun ChannelDrawer(
    channels: List<ChannelEntity>,
    currentNumber: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    onManage: (() -> Unit)? = null,
) {
    var favOnly by remember { mutableStateOf(true) }
    val favorites = channels.filter { it.isFavorite }.sortedBy { it.favoriteOrder }
    val showingFav = favOnly && favorites.isNotEmpty()
    val display = if (showingFav) favorites else channels
    val currentIndex = display.indexOfFirst { it.number == currentNumber }
    val focusRow = currentIndex.coerceAtLeast(0)
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = (focusRow - 4).coerceAtLeast(0),
    )
    val initialFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(60)
        runCatching { initialFocus.requestFocus() }
    }

    Row(Modifier.fillMaxHeight().width(441.dp)) {
        Column(
            Modifier
                .fillMaxHeight()
                .width(440.dp)
                .background(AntenaColors.Surface)
                .onPreviewKeyEvent { event ->
                    if (event.key == Key.Back || event.key == Key.DirectionLeft) {
                        if (event.type == KeyEventType.KeyUp) onDismiss()
                        true
                    } else {
                        false
                    }
                },
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "KANALLAR",
                    style = AntenaType.DrawerTitle,
                    color = AntenaColors.TextPrimary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${display.size}",
                    style = AntenaType.Clock,
                    color = AntenaColors.TextSecondary,
                )
            }

            // Sekme şeridi: Özel Liste (varsayılan) / Tüm Kanallar
            Row(Modifier.fillMaxWidth()) {
                DrawerTab("ÖZEL LİSTE", selected = showingFav, modifier = Modifier.weight(1f)) {
                    favOnly = true
                }
                DrawerTab("TÜM KANALLAR", selected = !showingFav, modifier = Modifier.weight(1f)) {
                    favOnly = false
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(AntenaColors.Border),
            )

            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(vertical = 8.dp),
                // Alt bilgi satırı ("Telefonla Yönet") her zaman görünür kalsın.
                modifier = Modifier.weight(1f),
            ) {
                itemsIndexed(display, key = { _, channel -> channel.id }) { index, channel ->
                    ChannelRow(
                        channel = channel,
                        isCurrent = channel.number == currentNumber,
                        modifier = if (index == focusRow) {
                            Modifier.focusRequester(initialFocus)
                        } else {
                            Modifier
                        },
                    ) {
                        onSelect(channels.indexOf(channel))
                    }
                }
            }

            // Alt giriş: telefon paneli (kilit modunda hiç gösterilmez)
            if (onManage != null) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(AntenaColors.Border),
                )
                ManageEntryRow(onClick = onManage)
            }
        }
        // Kenarlık şeridi
        Box(
            Modifier
                .fillMaxHeight()
                .width(1.dp)
                .background(AntenaColors.Border),
        )
    }
}

/** Çekmece üstündeki "Özel Liste" / "Tüm Kanallar" sekmesi. */
@Composable
private fun DrawerTab(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .background(if (focused) AntenaColors.OverlayScrim else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label,
            style = AntenaType.DrawerNumber,
            color = when {
                selected -> AntenaColors.Accent
                focused -> AntenaColors.TextPrimary
                else -> AntenaColors.TextSecondary
            },
        )
        Box(
            Modifier
                .padding(top = 6.dp)
                .width(44.dp)
                .height(2.dp)
                .background(if (selected) AntenaColors.Accent else Color.Transparent),
        )
    }
}

/** Çekmecenin altındaki "Telefonla Yönet" satırı. */
@Composable
private fun ManageEntryRow(onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .background(if (focused) AntenaColors.OverlayScrim else Color.Transparent)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(if (focused) AntenaColors.Accent else Color.Transparent),
        )
        Text(
            "TELEFONLA YÖNET",
            style = AntenaType.DrawerNumber,
            color = if (focused) AntenaColors.Accent else AntenaColors.TextSecondary,
        )
    }
}

@Composable
private fun ChannelRow(
    channel: ChannelEntity,
    isCurrent: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .background(if (focused) AntenaColors.OverlayScrim else Color.Transparent)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Odak göstergesi: amber şerit
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(if (focused) AntenaColors.Accent else Color.Transparent),
        )
        Text(
            channel.number.toString().padStart(3, '0'),
            style = AntenaType.DrawerNumber,
            color = if (focused || isCurrent) AntenaColors.Accent else AntenaColors.TextSecondary,
            modifier = Modifier.width(46.dp),
        )
        Text(
            channel.name,
            style = AntenaType.DrawerName,
            color = if (focused || isCurrent) AntenaColors.TextPrimary else AntenaColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
