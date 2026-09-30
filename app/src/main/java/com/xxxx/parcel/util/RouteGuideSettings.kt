package com.xxxx.parcel.util

import android.content.Context

/**
 * 「步行提示与路线图示」的呈现开关（用户 2026-10-01：功能都做出来，先用开关暴露多个状态，
 * 实机看过后再决定最终位置）。
 *
 * 全部落在与取件码同一个 `parcel_prefs` 里；**关掉任何一个都回到 0.1.9 的行为**（回归保护）。
 */
private const val PREFS = "parcel_prefs"
private const val KEY_TEXT = "route_guide_text_placement"
private const val KEY_MAP = "route_guide_map_placement"
private const val KEY_DETAIL = "route_guide_detail"
private const val KEY_MAP_VIEW = "route_guide_map_view"
private const val KEY_MAP_PAGE = "route_guide_map_page"
private const val KEY_MAP_HEIGHT = "route_guide_map_height"

private fun guidePrefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

/** 文字提示（「向东 5 格 → 向北 6 格」）出现在哪里。 */
enum class GuideTextPlacement(val label: String) {
    OFF("关闭"),
    HOME("仅首页"),
    ROUTE("仅路线页"),
    BOTH("两处");

    val onHome: Boolean get() = this == HOME || this == BOTH
    val onRoute: Boolean get() = this == ROUTE || this == BOTH

    companion object {
        fun from(value: String?): GuideTextPlacement =
            entries.firstOrNull { it.name == value } ?: BOTH
    }
}

/** 图示窗格（1/3 屏，矢量绘制）出现在哪里。 */
enum class GuideMapPlacement(val label: String) {
    OFF("关闭"),
    ROUTE_INLINE("路线页内嵌"),
    ROUTE_OVERLAY("路线页底部浮层"),
    HOME_OVERLAY("首页底部浮层");

    val onRoute: Boolean get() = this == ROUTE_INLINE || this == ROUTE_OVERLAY
    val onHome: Boolean get() = this == HOME_OVERLAY

    companion object {
        fun from(value: String?): GuideMapPlacement =
            entries.firstOrNull { it.name == value } ?: ROUTE_OVERLAY
    }
}

/** 提示的详细程度。 */
enum class GuideDetail(val label: String) {
    /** 只管方向与格数：「向东 5 格」 */
    BRIEF("简洁"),

    /** 加上走廊名/货架地标/区内走位：「沿第1条横走廊（B/A 排之间）向东 5 格」 */
    FULL("详细");

    companion object {
        fun from(value: String?): GuideDetail = entries.firstOrNull { it.name == value } ?: FULL
    }
}

/** 图示窗格的默认视图（运行时可一键切换）。 */
enum class GuideMapView(val label: String) {
    OVERVIEW("全览"),
    CLOSEUP("特写跟随");

    companion object {
        fun from(value: String?): GuideMapView = entries.firstOrNull { it.name == value } ?: OVERVIEW
    }
}

fun getGuideTextPlacement(context: Context): GuideTextPlacement =
    GuideTextPlacement.from(guidePrefs(context).getString(KEY_TEXT, null))

fun saveGuideTextPlacement(context: Context, value: GuideTextPlacement) {
    guidePrefs(context).edit().putString(KEY_TEXT, value.name).apply()
}

fun getGuideMapPlacement(context: Context): GuideMapPlacement =
    GuideMapPlacement.from(guidePrefs(context).getString(KEY_MAP, null))

fun saveGuideMapPlacement(context: Context, value: GuideMapPlacement) {
    guidePrefs(context).edit().putString(KEY_MAP, value.name).apply()
}

fun getGuideDetail(context: Context): GuideDetail =
    GuideDetail.from(guidePrefs(context).getString(KEY_DETAIL, null))

fun saveGuideDetail(context: Context, value: GuideDetail) {
    guidePrefs(context).edit().putString(KEY_DETAIL, value.name).apply()
}

fun getGuideMapView(context: Context): GuideMapView =
    GuideMapView.from(guidePrefs(context).getString(KEY_MAP_VIEW, null))

fun saveGuideMapView(context: Context, value: GuideMapView) {
    guidePrefs(context).edit().putString(KEY_MAP_VIEW, value.name).apply()
}

/**
 * 「独立地图页」（用户 2026-10-01）：一个**以地图为主**的页面 ——
 * 顶部 = 当前要取的取件码、中间 = 地图（含货架旁取件码）、底部 = 条码。
 * 打开开关后，首页右上角菜单里会出现入口。
 */
fun isMapPageEnabled(context: Context): Boolean =
    guidePrefs(context).getBoolean(KEY_MAP_PAGE, false)

fun saveMapPageEnabled(context: Context, value: Boolean) {
    guidePrefs(context).edit().putBoolean(KEY_MAP_PAGE, value).apply()
}

/** 首页地图窗格高度（dp，用户上下拖动调节后持久化；0 = 未设过，用默认比例）。 */
fun getGuideMapHeightDp(context: Context): Int =
    guidePrefs(context).getInt(KEY_MAP_HEIGHT, 0).coerceIn(0, 900)

fun saveGuideMapHeightDp(context: Context, valueDp: Int) {
    guidePrefs(context).edit().putInt(KEY_MAP_HEIGHT, valueDp.coerceIn(0, 900)).apply()
}
