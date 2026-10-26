package com.glassremote.domain.model

enum class BackgroundStyle { Glass, Solid, Oled, Image }

data class GlassStyle(
    val fontScale: Float = 1f,
    val fontWeight: Int = 400,
    val background: BackgroundStyle = BackgroundStyle.Glass,
    val imageUri: String? = null,
    val radius: Float = 1f,
)
