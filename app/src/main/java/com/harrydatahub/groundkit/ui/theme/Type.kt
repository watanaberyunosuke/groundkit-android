package com.harrydatahub.groundkit.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// One step larger than Material's defaults: read at arm's length, outdoors, often in a hurry.
private val base = Typography()

val Typography = Typography(
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
    titleLarge = base.titleLarge.copy(fontSize = 23.sp, fontWeight = FontWeight.Bold),
    titleMedium = base.titleMedium.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = base.bodyLarge.copy(fontSize = 17.sp, lineHeight = 24.sp),
    bodyMedium = base.bodyMedium.copy(fontSize = 15.sp, lineHeight = 21.sp),
    bodySmall = base.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = base.labelLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = base.labelMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
    labelSmall = base.labelSmall.copy(fontSize = 12.sp),
)

/** Raw METAR / TAF / NOTAM text. */
val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 20.sp)

/** Flight numbers and times on the boards. */
val FlightCodeStyle = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")
val BoardTimeStyle = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")
