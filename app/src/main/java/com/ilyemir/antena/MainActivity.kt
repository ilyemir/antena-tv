package com.ilyemir.antena

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
import com.ilyemir.antena.ui.PlayerScreen
import com.ilyemir.antena.ui.SetupScreen
import com.ilyemir.antena.ui.theme.AntenaColors
import com.ilyemir.antena.ui.theme.AntenaTheme
import com.ilyemir.antena.ui.theme.AntenaType

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            AntenaTheme {
                AntenaApp()
            }
        }
    }
}

@Composable
fun AntenaApp(viewModel: AntenaViewModel = viewModel()) {
    val channels by viewModel.channels.collectAsState()
    val bootstrapping by viewModel.bootstrapping.collectAsState()
    when {
        // Veritabanı henüz okunmadı veya gömülü havuz ilk açılışta yükleniyor
        channels == null || (channels!!.isEmpty() && bootstrapping) -> Splash()

        // Kurulum: hiç kanal yoksa M3U listesi adresi istenir
        channels!!.isEmpty() -> SetupScreen(viewModel)

        // Doğrudan yayın: menü göstermeden son izlenen kanalı tam ekran aç
        else -> PlayerScreen(
            viewModel = viewModel,
            channels = channels!!,
            initialChannelNumber = viewModel.lastChannelNumber.value,
        )
    }
}

@Composable
private fun Splash() {
    Box(
        Modifier
            .fillMaxSize()
            .background(AntenaColors.Background),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = AntenaColors.Accent)) { append("AN") }
                withStyle(SpanStyle(color = AntenaColors.TextPrimary)) { append("TENA") }
            },
            style = AntenaType.Wordmark,
        )
    }
}
