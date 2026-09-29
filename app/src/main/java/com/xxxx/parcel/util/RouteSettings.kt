package com.xxxx.parcel.util

import android.content.Context

/**
 * 布局参数的持久化（Android 侧）。
 *
 * 之所以把「排字母序列」和几个距离参数做成**可编辑**：踩点图给出的是**相对结构**（谁挨着谁、
 * 哪条通道服务哪两排），绝对格数（通道间距、入口到通道的距离）是示意图上的估算。
 * 与其等实测，不如让用户自己在界面里校准——引擎会如实报告无法定位的取件码，不会静默算错。
 */
private const val PREFS = "parcel_prefs"
private const val KEY_ROW_LETTERS = "route_row_letters"
private const val KEY_RETURN_TO_ENTRANCE = "route_return_to_entrance"
private const val KEY_AISLE_SPACING = "route_aisle_spacing"
private const val KEY_DOOR_TO_SPINE = "route_door_to_spine"
private const val KEY_EXIT_DEPTH = "route_exit_depth"
private const val KEY_EXIT_LATERAL = "route_exit_lateral"

private fun routePrefs(context: Context) =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

/** 解析用户填的字母序列，容忍逗号/空格/换行/全角逗号/顿号分隔。 */
fun parseRowLetters(text: String): List<Char> =
    text.split(',', '，', ' ', '\n', '\t', '、')
        .mapNotNull { it.trim().uppercase().firstOrNull() }
        .filter { it in 'A'..'Z' }
        .distinct()

fun getRouteRowLetters(context: Context): List<Char> {
    val raw = routePrefs(context).getString(KEY_ROW_LETTERS, null)
    val parsed = parseRowLetters(raw.orEmpty())
    return parsed.ifEmpty { SiteLayout.DEFAULT_ROW_LETTERS }
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

/** 相邻两条横向通道之间的纵向格数。 */
fun getRouteAisleSpacing(context: Context): Int =
    routePrefs(context).getInt(KEY_AISLE_SPACING, SiteLayout.default().aisleSpacingTiles)

fun saveRouteAisleSpacing(context: Context, value: Int) {
    routePrefs(context).edit().putInt(KEY_AISLE_SPACING, value).apply()
}

/** 入口到主纵向通道的横向格数。 */
fun getRouteDoorToSpine(context: Context): Int =
    routePrefs(context).getInt(KEY_DOOR_TO_SPINE, SiteLayout.default().doorToSpineTiles)

fun saveRouteDoorToSpine(context: Context, value: Int) {
    routePrefs(context).edit().putInt(KEY_DOOR_TO_SPINE, value).apply()
}

/** 出口（西侧大门）的深度：沿主通道从入口方向算起的格数。 */
fun getRouteExitDepth(context: Context): Int =
    routePrefs(context).getInt(KEY_EXIT_DEPTH, SiteLayout.default().exitDepthTiles)

fun saveRouteExitDepth(context: Context, value: Int) {
    routePrefs(context).edit().putInt(KEY_EXIT_DEPTH, value).apply()
}

/** 出口距主通道的横向格数。 */
fun getRouteExitLateral(context: Context): Int =
    routePrefs(context).getInt(KEY_EXIT_LATERAL, SiteLayout.default().exitLateralTiles)

fun saveRouteExitLateral(context: Context, value: Int) {
    routePrefs(context).edit().putInt(KEY_EXIT_LATERAL, value).apply()
}

/** 读出当前生效的场地布局。 */
fun getSiteLayout(context: Context): SiteLayout = SiteLayout.default().copy(
    rowLetters = getRouteRowLetters(context),
    aisleSpacingTiles = getRouteAisleSpacing(context),
    doorToSpineTiles = getRouteDoorToSpine(context),
    exitDepthTiles = getRouteExitDepth(context),
    exitLateralTiles = getRouteExitLateral(context),
)

// ===== 首页列表「按取件路线排序」=====

private const val KEY_ROUTE_SORT_LIST = "route_sort_list"

/** 首页「快递站」列表是否按最优取件顺序排列（默认开）。 */
fun isRouteSortList(context: Context): Boolean =
    routePrefs(context).getBoolean(KEY_ROUTE_SORT_LIST, true)

fun saveRouteSortList(context: Context, value: Boolean) {
    routePrefs(context).edit().putBoolean(KEY_ROUTE_SORT_LIST, value).apply()
}
