package com.bighelp.droidhelp.designsystem

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Material 3 typography scale for the Hermes Agent design system.
 * 
 * Defines the full set of text styles — display, headline, title, body, and label — 
 * including font families, font weights, line heights, and font sizes.
 * All values follow the Material 3 typography specification.
 */
object Typography {

    /** Display text style — the largest, most prominent text style. */
    val Display: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 57.sp,
        lineHeight = 64.sp
    )

    /** Headline text style — large headings. */
    val Headline: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 32.sp,
        lineHeight = 40.sp
    )

    /** Title text style — medium-large titles. */
    val Title: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 22.sp,
        lineHeight = 28.sp
    )

    /** Body text style — regular body text. */
    val Body: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp
    )

    /** Label text style — small, supporting text. */
    val Label: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp
    )
}

