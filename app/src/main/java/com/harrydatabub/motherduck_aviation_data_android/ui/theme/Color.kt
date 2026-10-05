package com.harrydatabub.motherduck_aviation_data_android.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Colours with a meaning: delay status (red / amber / green), flight category, map
 * paths. Status is never shown by colour alone; text and icons carry it too.
 */
@Immutable
data class StatusColors(
    val green: Color,
    val amber: Color,
    val red: Color,
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
    green = Color(0xFF15803D),
    amber = Color(0xFFB45309),
    red = Color(0xFFC81E1E),
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
    green = Color(0xFF34D399),
    amber = Color(0xFFFBBF24),
    red = Color(0xFFF87171),
    unknown = Color(0xFF9AA0A8),
    vfr = Color(0xFF34D399),
    mvfr = Color(0xFF60A5FA),
    ifr = Color(0xFFF87171),
    lifr = Color(0xFFE879F9),
    arrivalPath = Color(0xFF60A5FA),
    departurePath = Color(0xFFFB923C),
    ground = Color(0xFF5B616A),
    otherTraffic = Color(0xFF6B7179),
    mapBackground = Color(0xFF1F2226),
    halo = Color(0xFF0E1013),
)

val LocalStatusColors = staticCompositionLocalOf { LightStatusColors }

// Material roles. Light: navy on near-white. Dark: hi-vis amber on near-black for night shifts.
val Navy = Color(0xFF0B3D91)
val NavyContainer = Color(0xFFD9E3F8)
val HiVis = Color(0xFFFFC93C)
val HiVisContainer = Color(0xFF4A3A00)
