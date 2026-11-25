package com.ilyemir.antena.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import com.ilyemir.antena.R

/**
 * Monolithic Broadcast Console — renk tokenları.
 * Arka plan #08090A, yüzey #111317, ana metin #F4F4F5, ikincil #8E95A2,
 * odak/aktif vurgu #E5A93C (Broadcast Amber), kenarlık #23272F.
 */
object AntenaColors {
    val Background = Color(0xFF08090A)
    val Surface = Color(0xFF111317)
    val TextPrimary = Color(0xFFF4F4F5)
    val TextSecondary = Color(0xFF8E95A2)
    val Accent = Color(0xFFE5A93C)
    val Border = Color(0xFF23272F)
    /** Video üzerine bindirilen paneller için %80 opaklıkli konsol siyahı. */
    val OverlayScrim = Color(0xCC08090A)
}

val SpaceGrotesk = FontFamily(
    Font(R.font.space_grotesk_medium, FontWeight.Medium),
    Font(R.font.space_grotesk_bold, FontWeight.Bold),
)

val PlexSans = FontFamily(
    Font(R.font.ibm_plex_sans_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_sans_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_sans_semibold, FontWeight.SemiBold),
)

val PlexMono = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
)

/**
 * Tipografi: başlık/kanal adları Space Grotesk, gövde IBM Plex Sans,
 * kanal numaraları ve saatler IBM Plex Mono.
 */
object AntenaType {
    val Wordmark = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold, fontSize = 56.sp)

    val ChannelNumber = TextStyle(fontFamily = PlexMono, fontWeight = FontWeight.Medium, fontSize = 38.sp)
    val ChannelName = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium, fontSize = 26.sp)
    val NumberEntry = TextStyle(fontFamily = PlexMono, fontWeight = FontWeight.Medium, fontSize = 56.sp)
    val Clock = TextStyle(fontFamily = PlexMono, fontWeight = FontWeight.Medium, fontSize = 18.sp)
    val ExitHint = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.Medium, fontSize = 15.sp)

    val DrawerTitle = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold, fontSize = 18.sp)
    val DrawerNumber = TextStyle(fontFamily = PlexMono, fontWeight = FontWeight.Normal, fontSize = 17.sp)
    val DrawerName = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium, fontSize = 21.sp)

    val Digit = TextStyle(fontFamily = PlexMono, fontWeight = FontWeight.Medium, fontSize = 26.sp)
    val DigitAction = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.Medium, fontSize = 14.sp)

    val Body = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.Normal, fontSize = 16.sp)
    val BodyMedium = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.Medium, fontSize = 14.sp)
    val Label = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.Normal, fontSize = 12.sp)
    val LabelMono = TextStyle(fontFamily = PlexMono, fontWeight = FontWeight.Normal, fontSize = 12.sp)
    val Field = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.Normal, fontSize = 16.sp)
}

@Composable
fun AntenaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = AntenaColors.Background,
            onBackground = AntenaColors.TextPrimary,
            surface = AntenaColors.Surface,
            onSurface = AntenaColors.TextPrimary,
            primary = AntenaColors.Accent,
            onPrimary = AntenaColors.Background,
        ),
    ) {
        content()
    }
}
