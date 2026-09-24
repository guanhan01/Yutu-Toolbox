package com.mcp.toolbox.core.design.theme

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private val Context.themeDataStore: DataStore<Preferences> by preferencesDataStore(name = "miuix_theme")

/**
 * 主题控制器：内存态 StateFlow 保证「改完立即生效」，
 * DataStore 负责重启后的持久化。
 */
class ThemeController(
    private val store: DataStore<Preferences>,
    private val scope: CoroutineScope,
) {

    private val _config = MutableStateFlow(ThemeConfig())
    val config: StateFlow<ThemeConfig> = _config.asStateFlow()

    init {
        // 冷启动：先同步把首帧配置读出来，否则首帧会用默认主题渲染，再异步闪成用户主题。
        // DataStore 首次读取是一次小文件 IO，在 Application.onCreate 里只阻塞几毫秒，比可见的闪屏划算。
        runCatching { runBlocking { store.data.first() } }.getOrNull()?.let { _config.value = it.read() }
        scope.launch {
            store.data.collect { prefs -> _config.value = prefs.read() }
        }
    }

    private var persistJob: Job? = null

    fun update(transform: (ThemeConfig) -> ThemeConfig) {
        val next = transform(_config.value)
        _config.value = next
        // 滑杆拖动时这个方法每帧都会被调到。内存态立刻生效，落盘做去抖：
        // 原来每帧一次 store.edit（写盘 + 回读 collect）会让 thumb 掉帧，还会被回读的旧值拽回去。
        persistJob?.cancel()
        persistJob = scope.launch {
            delay(PersistDebounceMs)
            store.edit { it.write(next) }
        }
    }

    fun setRadius(dp: Float) = update {
        it.copy(radiusScaleDp = dp.coerceIn(ThemeConfig.MinRadius, ThemeConfig.MaxRadius))
    }

    fun setSaturation(value: Float) = update {
        it.copy(saturation = value.coerceIn(ThemeConfig.MinSaturation, ThemeConfig.MaxSaturation))
    }

    fun setFontScale(value: Float) = update {
        it.copy(fontScale = value.coerceIn(ThemeConfig.MinFontScale, ThemeConfig.MaxFontScale))
    }

    fun setMotionScale(value: Float) = update {
        it.copy(motionScale = value.coerceIn(ThemeConfig.MinMotionScale, ThemeConfig.MaxMotionScale))
    }

    fun resetToDefault() {
        _config.value = ThemeConfig()
        scope.launch { store.edit { it.clear() } }
    }

    private fun Preferences.read(): ThemeConfig = ThemeConfig(
        source = enumOr(this[KeySource], ThemeConfig().source),
        darkMode = enumOr(this[KeyDarkMode], DarkModeSetting.FOLLOW_SYSTEM),
        perModuleDark = this[KeyPerModuleDark]?.split(',')?.filter { it.isNotBlank() }?.toSet() ?: emptySet(),
        customSeedArgb = this[KeySeed] ?: ThemeConfig.BrandSeedArgb,
        presetId = this[KeyPreset] ?: ThemeConfig().presetId,
        amoled = this[KeyAmoled] ?: false,
        dynamicEnabled = this[KeyDynamic] ?: true,
        contrast = enumOr(this[KeyContrast], ContrastSetting.STANDARD),
        paletteStyle = enumOr(this[KeyStyle], PaletteStyleSetting.TONAL),
        radiusScaleDp = this[KeyRadius] ?: ThemeConfig().radiusScaleDp,
        saturation = this[KeySaturation] ?: 1f,
        fontScale = this[KeyFontScale] ?: 1f,
        lineHeightScale = this[KeyLineHeight] ?: 1f,
        motionScale = this[KeyMotion] ?: 1f,
        uiStyle = enumOr(this[KeyUiStyle], UiStyle.MIUIX),
    )

    private fun MutablePreferences.write(config: ThemeConfig) {
        this[KeySource] = config.source.name
        this[KeyDarkMode] = config.darkMode.name
        this[KeyPerModuleDark] = config.perModuleDark.joinToString(",")
        this[KeySeed] = config.customSeedArgb
        this[KeyPreset] = config.presetId
        this[KeyAmoled] = config.amoled
        this[KeyDynamic] = config.dynamicEnabled
        this[KeyContrast] = config.contrast.name
        this[KeyStyle] = config.paletteStyle.name
        this[KeyRadius] = config.radiusScaleDp
        this[KeySaturation] = config.saturation
        this[KeyFontScale] = config.fontScale
        this[KeyLineHeight] = config.lineHeightScale
        this[KeyMotion] = config.motionScale
        this[KeyUiStyle] = config.uiStyle.name
    }

    private inline fun <reified T : Enum<T>> enumOr(raw: String?, fallback: T): T =
        raw?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback

    companion object {
        private const val PersistDebounceMs = 150L

        private val KeySource = stringPreferencesKey("theme_source")
        private val KeyDarkMode = stringPreferencesKey("dark_mode")
        private val KeyPerModuleDark = stringPreferencesKey("per_module_dark")
        private val KeySeed = longPreferencesKey("custom_seed")
        private val KeyPreset = stringPreferencesKey("preset_id")
        private val KeyAmoled = booleanPreferencesKey("amoled")
        private val KeyDynamic = booleanPreferencesKey("dynamic_enabled")
        private val KeyContrast = stringPreferencesKey("contrast")
        private val KeyStyle = stringPreferencesKey("palette_style")
        private val KeyRadius = floatPreferencesKey("radius_scale")
        private val KeySaturation = floatPreferencesKey("saturation")
        private val KeyFontScale = floatPreferencesKey("font_scale")
        private val KeyLineHeight = floatPreferencesKey("line_height")
        private val KeyMotion = floatPreferencesKey("motion_scale")
        private val KeyUiStyle = stringPreferencesKey("ui_style")

        fun create(context: Context, scope: CoroutineScope): ThemeController =
            ThemeController(context.applicationContext.themeDataStore, scope)
    }
}
