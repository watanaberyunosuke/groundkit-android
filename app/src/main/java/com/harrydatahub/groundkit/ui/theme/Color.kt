package com.harrydatahub.groundkit.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Colours with a meaning: delay status (red / amber / green), flight category, map
 * paths. Status is never shown by colour alone; text and icons carry it too. Values are
 * shared with the dashboard, website and iOS app (DESIGN.md in the aviation repo).
 *
 * Status colours are muted, as on iOS: a soft tone for icons, text and map marks, and a
 * pale container to fill behind them. Each tone passes 4.5:1 on its container.
 */
@Immutable
data class StatusColors(
    val green: Color,
    val amber: Color,
    val red: Color,
    val info: Color,
    val greenContainer: Color,
    val amberContainer: Color,
    val redContainer: Color,
    val infoContainer: Color,
    val unknown: Color,
    val vfr: Color,
    val mvfr: Color,
    val ifr: Color,
    val lifr: Color,
    val arrivalPath: Color,
    val departurePath: Color,
    val ground: Color,
    val otherTraffic: Color,
    val mapBackground: Color,
    val halo: Color,
)

val LightStatusColors = StatusColors(
    green = Color(0xFF2B744A),
    amber = Color(0xFF94591A),
    red = Color(0xFFAD3B3B),
    info = Color(0xFF2F6299),
    greenContainer = Color(0xFFE3F0E7),
    amberContainer = Color(0xFFF7ECDB),
    redContainer = Color(0xFFF7E3E1),
    infoContainer = Color(0xFFE2EBF6),
    unknown = Color(0xFF6B7280),
    vfr = Color(0xFF15803D),
    mvfr = Color(0xFF1D4ED8),
    ifr = Color(0xFFC81E1E),
    lifr = Color(0xFFA21CAF),
    arrivalPath = Color(0xFF2563EB),
    departurePath = Color(0xFFEA580C),
    ground = Color(0xFF9CA3AF),
    otherTraffic = Color(0xFFA3A9B1),
    mapBackground = Color(0xFFEEF1F4),
    halo = Color(0xFFFFFFFF),
)

val DarkStatusColors = StatusColors(
    green = Color(0xFF86C9A0),
    amber = Color(0xFFE2B672),
    red = Color(0xFFEE9B95),
    info = Color(0xFF94B8E3),
    greenContainer = Color(0xFF1E3427),
    amberContainer = Color(0xFF3A2F1C),
    redContainer = Color(0xFF3E2525),
    infoContainer = Color(0xFF1D2B3F),
    unknown = Color(0xFF9AA0A8),
    vfr = Color(0xFF22C55E),
    mvfr = Color(0xFF60A5FA),
    ifr = Color(0xFFF87171),
    lifr = Color(0xFFE879F9),
    arrivalPath = Color(0xFF60A5FA),
    departurePath = Color(0xFFFB923C),
    ground = Color(0xFF5B616A),
    otherTraffic = Color(0xFF6B7179),
    mapBackground = Color(0xFF1F2226),
    halo = Color(0xFF121417),
)

val LocalStatusColors = staticCompositionLocalOf { LightStatusColors }

// Brand, light blue to navy. Light: navy primary on near-white. Dark: light blue primary,
// with navy text on it. Amber stays a caution colour only, so the brand never reads as a status.
val Navy = Color(0xFF0B3D91)
val NavyContainer = Color(0xFFD9E3F8)
val NavyInk = Color(0xFF0B1B36)
val LightBlue = Color(0xFF60A5FA)
val LightBlueContainer = Color(0xFF12305F)

/** Text on a filled [this]: white where it reaches 4.5:1, otherwise near-black. */
fun Color.readableContent(): Color = if (luminance() > 0.18f) Color(0xFF111418) else Color.White
