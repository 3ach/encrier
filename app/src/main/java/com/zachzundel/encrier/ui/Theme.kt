package com.zachzundel.encrier.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Grayscale for e-paper, but notebook-soft: serif content type, hairline
// rules, a margin line, quiet lowercase controls. Still no animation.
val InkBlack = Color(0xFF1A1A1A)
val InkWhite = Color(0xFFFFFFFF)
val InkGray = Color(0xFF6E6E6E)
val InkFaint = Color(0xFFD4D4D4)   // ruled lines
val InkMargin = Color(0xFFB5B5B5)  // notebook margin line
val Mono = FontFamily.Monospace
val Serif = FontFamily.Serif

@Composable
fun EncrierTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = InkBlack,
            onPrimary = InkWhite,
            background = InkWhite,
            onBackground = InkBlack,
            surface = InkWhite,
            onSurface = InkBlack,
            outline = InkBlack,
        ),
        content = content,
    )
}

/** Clickable without the Material ripple — no animation on e-paper (spec §1). */
fun Modifier.hardClickable(onClick: () -> Unit): Modifier = composed {
    clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick,
    )
}

/** Quiet rounded button. Selected state = inverted fill. */
@Composable
fun HardButton(
    label: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(7.dp)
    Text(
        text = label,
        color = if (selected) InkWhite else InkBlack,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        fontFamily = Mono,
        modifier = modifier
            .border(1.5.dp, InkBlack, shape)
            .background(if (selected) InkBlack else InkWhite, shape)
            .hardClickable(onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
}
