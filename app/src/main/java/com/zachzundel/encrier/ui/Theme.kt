package com.zachzundel.encrier.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.remember
import androidx.compose.ui.composed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Grayscale, high-contrast, hard-edged (spec §1). No shadows, gradients, animation.
val InkBlack = Color(0xFF000000)
val InkWhite = Color(0xFFFFFFFF)
val InkGray = Color(0xFF666666)
val InkFaint = Color(0xFFBBBBBB)
val Mono = FontFamily.Monospace

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

/** Square, bordered, stylus-tappable button. Selected state = inverted fill. */
@Composable
fun HardButton(
    label: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        color = if (selected) InkWhite else InkBlack,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = Mono,
        modifier = modifier
            .border(2.dp, InkBlack)
            .background(if (selected) InkBlack else InkWhite)
            .hardClickable(onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}
