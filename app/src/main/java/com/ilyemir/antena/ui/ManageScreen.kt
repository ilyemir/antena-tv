package com.ilyemir.antena.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.ilyemir.antena.server.localIpAddress
import com.ilyemir.antena.ui.theme.AntenaColors
import com.ilyemir.antena.ui.theme.AntenaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalContext

/**
 * "Telefonla Yönet": aynı Wi-Fi ağındaki telefonda açılacak panel adresini ve
 * QR kodunu gösterir. QR matrisi zxing ile üretilir, Compose Canvas ile çizilir.
 */
@Composable
fun ManageScreen(port: Int, onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)
    val context = LocalContext.current
    val ip = context.localIpAddress()
    val url = "http://$ip:$port"

    val matrix by produceState<Array<BooleanArray>?>(initialValue = null, url) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 0, 0)
                    .let { code ->
                        Array(code.height) { y ->
                            BooleanArray(code.width) { x -> code.get(x, y) }
                        }
                    }
            }.getOrNull()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(AntenaColors.Background.copy(alpha = 0.97f))
            .padding(48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(56.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("TELEFONLA YÖNET", style = AntenaType.DrawerTitle, color = AntenaColors.Accent)
                Text(
                    "Aynı Wi-Fi ağındaki telefonun Safari'sinde aşağıdaki adresi aç ya da QR kodunu okut.",
                    style = AntenaType.Body,
                    color = AntenaColors.TextSecondary,
                    modifier = Modifier.width(420.dp),
                )
                Box(
                    Modifier
                        .background(AntenaColors.Surface)
                        .border(1.dp, AntenaColors.Border)
                        .padding(horizontal = 22.dp, vertical = 16.dp),
                ) {
                    Text(url, style = AntenaType.ChannelNumber, color = AntenaColors.TextPrimary)
                }
                Text(
                    "Panel: kumanda · kanal düzeni · liste · kilit",
                    style = AntenaType.Label,
                    color = AntenaColors.TextSecondary,
                )
                Text(
                    "mDNS destekli ağlarda: http://antena.local:$port",
                    style = AntenaType.LabelMono,
                    color = AntenaColors.TextSecondary,
                )
                Text("Geri tuşu: kapat", style = AntenaType.LabelMono, color = AntenaColors.TextSecondary)
            }

            Box(
                Modifier
                    .size(300.dp)
                    .background(Color.White)
                    .padding(14.dp),
                contentAlignment = Alignment.Center,
            ) {
                matrix?.let { cells ->
                    Canvas(Modifier.fillMaxSize()) {
                        drawQr(cells, Color.Black)
                    }
                }
            }
        }

        Text(
            "ANTENA",
            style = AntenaType.LabelMono,
            color = AntenaColors.TextSecondary,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 4.dp),
        )
    }
}

private fun DrawScope.drawQr(cells: Array<BooleanArray>, color: Color) {
    val moduleSize = kotlin.math.min(size.width, size.height) / cells.size
    translate(
        left = (size.width - moduleSize * cells.size) / 2f,
        top = (size.height - moduleSize * cells.size) / 2f,
    ) {
        for (y in cells.indices) {
            for (x in cells[y].indices) {
                if (cells[y][x]) {
                    drawRect(
                        color = color,
                        topLeft = androidx.compose.ui.geometry.Offset(x * moduleSize, y * moduleSize),
                        size = androidx.compose.ui.geometry.Size(moduleSize + 0.35f, moduleSize + 0.35f),
                    )
                }
            }
        }
    }
}
