package com.glassremote.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/** Frosted-glass surface: translucent gradient fill, thin light border, soft depth shadow. */
fun Modifier.glass(shape: Shape, strong: Boolean = false): Modifier = this
    .shadow(20.dp, shape, clip = false, ambientColor = Color.Black, spotColor = Color.Black)
    .clip(shape)
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
        shape,
    )

/** Dark backdrop with soft blurred colour blobs so the glass has something to refract. */
@Composable
fun GlassBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxSize().background(Color(0xFF07080C))) {
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
        content()
    }
}
