package com.mcp.toolbox

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * 应用内语言：跟随系统 / 简体中文 / English。
 *
 * 宿主是 ComponentActivity（无 AppCompat）且 minSdk 26，所以不依赖 AppCompatDelegate：
 * 选择写进自己的 SharedPreferences，由 [wrap] 在 attachBaseContext 阶段同步包一层 Configuration；
 * API 33+ 再把选择回写系统 per-app language，让系统设置页与 app 内保持一致。
 */
object AppLanguage {
    const val SYSTEM = "system"
    const val ZH = "zh-CN"
    const val EN = "en"

    private const val PREFS = "app_language"
    private const val KEY = "tag"

    /** 选择项顺序即菜单顺序。 */
    val options: List<String> = listOf(SYSTEM, ZH, EN)

    fun current(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: SYSTEM

    fun apply(context: Context, tag: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, tag).commit()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                context.getSystemService(LocaleManager::class.java)?.applicationLocales =
                    if (tag == SYSTEM) {
                        LocaleList.getEmptyLocaleList()
                    } else {
                        LocaleList.forLanguageTags(tag)
                    }
            }
        }
    }

    /** attachBaseContext 阶段同步生效：非「跟随系统」时强制覆盖 Configuration。 */
    fun wrap(base: Context): Context {
        val tag = current(base)
        if (tag == SYSTEM) return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLocales(LocaleList(locale))
        return base.createConfigurationContext(config)
    }
}
