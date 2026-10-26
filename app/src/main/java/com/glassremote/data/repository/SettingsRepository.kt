package com.glassremote.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.glassremote.domain.model.BackgroundStyle
import com.glassremote.domain.model.GlassStyle
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsStore by preferencesDataStore("glass_settings")

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext private val ctx: Context) {
    private val kFont = floatPreferencesKey("font_scale")
    private val kWeight = intPreferencesKey("font_weight")
    private val kBg = stringPreferencesKey("background")
    private val kImage = stringPreferencesKey("image_uri")
    private val kRadius = floatPreferencesKey("radius")

    val style: Flow<GlassStyle> = ctx.settingsStore.data.map { p ->
        GlassStyle(
            fontScale = p[kFont] ?: 1f,
            fontWeight = p[kWeight] ?: 400,
            background = p[kBg]?.let { runCatching { BackgroundStyle.valueOf(it) }.getOrNull() }
                ?: BackgroundStyle.Glass,
            imageUri = p[kImage],
            radius = p[kRadius] ?: 1f,
        )
    }

    suspend fun setFontScale(v: Float) { ctx.settingsStore.edit { it[kFont] = v } }
    suspend fun setWeight(v: Int) { ctx.settingsStore.edit { it[kWeight] = v } }
    suspend fun setBackground(b: BackgroundStyle) { ctx.settingsStore.edit { it[kBg] = b.name } }
    suspend fun setRadius(v: Float) { ctx.settingsStore.edit { it[kRadius] = v } }
    suspend fun setImage(uri: String) {
        ctx.settingsStore.edit { it[kImage] = uri; it[kBg] = BackgroundStyle.Image.name }
    }
}
