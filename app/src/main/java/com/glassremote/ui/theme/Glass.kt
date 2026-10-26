package com.glassremote.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.glassremote.domain.model.BackgroundStyle
import com.glassremote.domain.model.GlassStyle

val LocalGlassStyle = compositionLocalOf { GlassStyle() }

/** Re-scales any rounded shape's corner radius (also turns circles into soft squares below 100%). */
private class ScaledCorners(private val base: CornerBasedShape, private val scale: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = (base.topStart.toPx(size, density) * scale)
            .coerceIn(0f, minOf(size.width, size.height) / 2f)
        return Outline.Rounded(RoundRect(0f, 0f, size.width, size.height, CornerRadius(r)))
    }
}

/** Frosted-glass surface. Corner radius follows the user's Settings. */
@Composable
fun Modifier.glass(shape: Shape, strong: Boolean = false): Modifier {
    val scale = LocalGlassStyle.current.radius
    val s: Shape = remember(shape, scale) {
        if (shape is CornerBasedShape) ScaledCorners(shape, scale) else shape
    }
    return this
        .shadow(20.dp, s, clip = false, ambientColor = Color.Black, spotColor = Color.Black)
        .clip(s)
        .background(
            Brush.verticalGradient(
                listOf(
                    Color.White.copy(alpha = if (strong) 0.26f else 0.14f),
                    Color.White.copy(alpha = if (strong) 0.14f else 0.05f),
                )
            )
        )
        .border(
            0.8.dp,
            Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.45f), Color.White.copy(alpha = 0.06f))),
            s,
        )
}

@Composable
private fun BoxScope.GlassBlobs() {
    Box(
        Modifier.size(320.dp).offset((-90).dp, (-60).dp)
            .blur(90.dp, BlurredEdgeTreatment.Unbounded)
            .background(Color(0xFF5B6CFF).copy(alpha = 0.55f), CircleShape)
    )
    Box(
        Modifier.align(Alignment.BottomEnd).size(280.dp).offset(80.dp, 60.dp)
            .blur(90.dp, BlurredEdgeTreatment.Unbounded)
            .background(Color(0xFFFF5FA2).copy(alpha = 0.40f), CircleShape)
    )
}

@Composable
fun GlassBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val style = LocalGlassStyle.current
    val base = when (style.background) {
        BackgroundStyle.Oled -> Color.Black
        BackgroundStyle.Solid -> Color(0xFF14161C)
        else -> Color(0xFF07080C)
    }
    Box(modifier.fillMaxSize().background(base)) {
        when (style.background) {
            BackgroundStyle.Glass -> GlassBlobs()
            BackgroundStyle.Image -> {
                if (style.imageUri != null) {
                    AsyncImage(
                        model = style.imageUri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().blur(28.dp),
                    )
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
                } else {
                    GlassBlobs()
                }
            }
            else -> Unit
        }
        content()
    }
}
