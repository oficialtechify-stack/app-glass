package com.example

import android.os.Build
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.matchParentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Reusable glass-like surface. The layered specular highlight works on Android 7+.
 * The blur applies to the panel's tint layer, not the scene behind it.
 */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    cornerRadius: Int = 26,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius.dp)
    Box(
        modifier = modifier
            .shadow(12.dp, shape, ambientColor = Color(0x502D8DE0))
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xB96183AA),
                        Color(0xA72A4364),
                        Color(0xC51B2B43)
                    )
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.48f), Color.White.copy(alpha = 0.09f))
                ),
                shape = shape
            ),
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Box(
                Modifier.matchParentSize()
                    .graphicsLayer {
                        renderEffect = BlurEffect(18f, 18f, TileMode.Clamp)
                    }
                    .background(Color(0x225D9BCF))
            )
        }
        content()
    }
}
