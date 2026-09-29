package com.xxxx.parcel.util

import android.content.Context

/**
 * 路线设置的持久化（Android 侧）。
 *
 * 2026-09-29 起场地模型改为**数据驱动**（可走格直接来自用户 Excel 的填充色，见 [SiteData]），
 * 所以旧的「排字母序列 / 通道间距 / 入口到主通道 / 出口位置 / 货架宽度」这些**估算参数全部作废**，
 * 只剩一个仍属未实测假设的旋钮：**J 柜列每列格数**（货位清单 Q1b/Q5 待确认，实测只到 `j5-21`）。
 */
private const val PREFS = "parcel_prefs"
private const val KEY_J_CELLS = "route_j_cells"

private fun routePrefs(context: Context) =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

/** J 柜列每列格数（默认 21；实测 `j5-21` ⇒ ≥21，具体值待用户确认）。 */
fun getRouteJCells(context: Context): Int =
    routePrefs(context).getInt(KEY_J_CELLS, RouteOptions.DEFAULT.jCellsPerColumn).coerceIn(1, 200)

fun saveRouteJCells(context: Context, value: Int) {
    routePrefs(context).edit().putInt(KEY_J_CELLS, value.coerceIn(1, 200)).apply()
}

/** 读出当前生效的场地参数。 */
fun getRouteOptions(context: Context): RouteOptions =
    RouteOptions(jCellsPerColumn = getRouteJCells(context))

// ===== 首页列表「按取件路线排序」=====

private const val KEY_ROUTE_SORT_LIST = "route_sort_list"

/** 首页「快递站」列表是否按最优取件顺序排列（默认开）。 */
fun isRouteSortList(context: Context): Boolean =
    routePrefs(context).getBoolean(KEY_ROUTE_SORT_LIST, true)

fun saveRouteSortList(context: Context, value: Boolean) {
    routePrefs(context).edit().putBoolean(KEY_ROUTE_SORT_LIST, value).apply()
}
