package com.xxxx.parcel.util

import android.content.Context

/**
 * 布局参数的持久化（Android 侧）。
 *
 * 之所以把「横排字母序列」做成**可编辑**：用户尚未现场核实字母到哪个为止，
 * 与其等，不如让他自己在界面里填对——引擎会如实报告无法定位的货格号，不会静默算错。
 */
private const val PREFS = "parcel_prefs"
private const val KEY_ROW_LETTERS = "route_row_letters"
private const val KEY_RETURN_TO_ENTRANCE = "route_return_to_entrance"

private fun routePrefs(context: Context) =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

/** 解析用户填的字母序列，容忍逗号/空格/换行/全角逗号分隔。 */
fun parseRowLetters(text: String): List<Char> =
    text.split(',', '，', ' ', '\n', '\t', '、')
        .mapNotNull { it.trim().uppercase().firstOrNull() }
        .filter { it in 'A'..'Z' }
        .distinct()

fun getRouteRowLetters(context: Context): List<Char> {
    val raw = routePrefs(context).getString(KEY_ROW_LETTERS, null)
    val parsed = parseRowLetters(raw.orEmpty())
    return parsed.ifEmpty { SiteLayout.default().rowLetters }
}

fun saveRouteRowLetters(context: Context, letters: List<Char>) {
    routePrefs(context).edit().putString(KEY_ROW_LETTERS, letters.joinToString(",")).apply()
}

/** 取完后是否折返回入口。为 false 时是敞开路径，最优解会把最深的一件排在最后。 */
fun isRouteReturnToEntrance(context: Context): Boolean =
    routePrefs(context).getBoolean(KEY_RETURN_TO_ENTRANCE, true)

fun saveRouteReturnToEntrance(context: Context, value: Boolean) {
    routePrefs(context).edit().putBoolean(KEY_RETURN_TO_ENTRANCE, value).apply()
}

/** 读出当前生效的场地布局。 */
fun getSiteLayout(context: Context): SiteLayout =
    SiteLayout.default().copy(rowLetters = getRouteRowLetters(context))
