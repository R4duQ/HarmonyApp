package com.harmony.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.harmony.core.ui.R

/*
 * Two variable fonts, both under the SIL Open Font License, both with ă â î ș ț:
 *
 *  - Bricolage Grotesque, only for names: songs, albums, artists, playlists,
 *    screen titles. A warm grotesque with optical sizes, like the type on a
 *    record label. Each size gets its own optical size (opsz = the size).
 *  - Figtree for everything you read and tap, with tabular figures so
 *    durations, counts and sample rates line up.
 *
 * Seven sizes in the whole app: 34 · 28 · 22 · 18 · 16 · 14 · 12. Twelve is
 * the floor. Glance surfaces (Now Playing, the mini player) carry their
 * meaning at 16 sp and above.
 */

private const val NameWeight = 650

/** Bricolage at one optical size, so every name is drawn for the size it is set at. */
@OptIn(ExperimentalTextApi::class)
private fun bricolage(opsz: Float): FontFamily = FontFamily(
    Font(
        R.font.bricolage_grotesque,
        weight = FontWeight(NameWeight),
        style = FontStyle.Normal,
        variationSettings = FontVariation.Settings(
            FontVariation.weight(NameWeight),
            FontVariation.Setting("opsz", opsz),
            FontVariation.Setting("wdth", 100f),
        ),
    ),
)

@OptIn(ExperimentalTextApi::class)
private fun figtreeFont(weight: Int) = Font(
    R.font.figtree,
    weight = FontWeight(weight),
    style = FontStyle.Normal,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Figtree at the four weights the scale uses. */
val Figtree = FontFamily(figtreeFont(400), figtreeFont(500), figtreeFont(600), figtreeFont(700))

private val Bricolage34 = bricolage(34f)
private val Bricolage28 = bricolage(28f)
private val Bricolage22 = bricolage(22f)
private val Bricolage18 = bricolage(18f)

private fun name(family: FontFamily, size: Int, line: Int, tracking: Double) = TextStyle(
    fontFamily = family,
    fontWeight = FontWeight(NameWeight),
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp,
)

private fun ui(size: Int, line: Int, weight: Int, tracking: Double = 0.0, tabular: Boolean = true) = TextStyle(
    fontFamily = Figtree,
    fontWeight = FontWeight(weight),
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp,
    fontFeatureSettings = if (tabular) "tnum" else null,
)

/** The named roles of the Listening Room scale. Screens pick a role, never a size. */
object RoomType {
    /** Screen title: Listen, Library, Add. */
    val display = name(Bricolage34, 34, 40, -0.6)
    /** The song on Now Playing. A glance role. */
    val nameXL = name(Bricolage28, 28, 34, -0.4)
    /** Album and playlist headers; the song in the mini player. A glance role. */
    val nameL = name(Bricolage22, 22, 28, -0.2)
    /** Section and card titles. */
    val nameM = name(Bricolage18, 18, 24, -0.1)
    /** The artist on Now Playing and in the mini player; the reason line. */
    val glance = ui(18, 24, 500)
    /** A list row's primary line; a dialog title. */
    val title = ui(16, 22, 600)
    val body = ui(16, 24, 400, tabular = false)
    /** Buttons. */
    val label = ui(16, 20, 600, 0.1)
    /** A list row's secondary line. */
    val bodySmall = ui(14, 20, 400, 0.1)
    /** Chips, tabs, segments. */
    val labelSmall = ui(14, 18, 600, 0.1)
    /** Format facts, durations, counts. */
    val caption = ui(14, 18, 500)
    /** Overlines and badges. The floor: nothing smaller. */
    val micro = ui(12, 16, 600, 0.4)
}

/**
 * The Material slots, filled from [RoomType], so stock components and screens
 * that still pick Material roles get the room's scale.
 */
val HarmonyTypography = Typography(
    displayLarge = RoomType.display,
    displayMedium = RoomType.display,
    displaySmall = RoomType.display,
    headlineLarge = RoomType.nameXL,
    headlineMedium = RoomType.nameL,
    headlineSmall = RoomType.nameM,
    titleLarge = RoomType.nameL,
    titleMedium = RoomType.title,
    titleSmall = RoomType.labelSmall,
    bodyLarge = RoomType.body,
    bodyMedium = RoomType.bodySmall,
    bodySmall = RoomType.micro.copy(fontWeight = FontWeight(400), letterSpacing = 0.2.sp),
    labelLarge = RoomType.label,
    labelMedium = RoomType.labelSmall,
    labelSmall = RoomType.micro,
)
