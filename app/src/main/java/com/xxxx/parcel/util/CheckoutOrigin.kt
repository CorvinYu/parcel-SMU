package com.xxxx.parcel.util

import android.content.Context
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 「刚取完的那一件」在哪 —— 用来把路线的起点从入口改成**现场那一点**（用户 2026-10-01）。
 *
 * 场景：在驿站里取完 ①，地图上的 ① 不该凭空消失，而应变成**灰点（已取）**，
 * 且下一段应从这一点出发去 ②，而不是又画一条「从入口到 ②」的线。
 *
 * 只认**时间窗内**的最近一次（默认 3 小时）：隔了一晚再来取件，仍应从入口进。
 */
private const val PREFS = "parcel_prefs"
private const val KEY_CHECKOUT = "route_checkout_origin"
private const val KEEP = 12

/** 默认时间窗：3 小时内算「同一场次」。 */
const val CHECKOUT_ORIGIN_WINDOW_MS = 3L * 60 * 60 * 1000

/** 上一次「刚取完」的位置。 */
data class CheckoutOrigin(val cell: GridCell, val label: String, val code: String)

/**
 * 地图上的「已取件」灰点（用户 2026-10-01）：取过的件不要从图上消失，标成灰色 ✓，路线从那一点接着走。
 */
data class CompletedMarker(val cell: GridCell, val label: String)

/** 把「刚取完的位置」变成地图上的灰点（没有就返回空）。 */
fun completedMarkersOf(origin: CheckoutOrigin?): List<CompletedMarker> =
    origin?.let { listOf(CompletedMarker(it.cell, it.code)) } ?: emptyList()

/**
 * 纯逻辑：从「已取件记录（取件码 → 时间）」里挑出可用作起点的取件码。
 * 记录按时间倒序存，取第一条且在窗口内；越界/过期/为空都返回 null。
 */
fun pickCheckoutOriginCode(
    entries: List<Pair<String, Long>>,
    now: Long,
    windowMs: Long = CHECKOUT_ORIGIN_WINDOW_MS,
): String? {
    val newest = entries.firstOrNull() ?: return null
    if (now - newest.second > windowMs || now < newest.second) return null
    return newest.first.takeIf { it.isNotBlank() }
}

/** 记一次「已取件」（取件码 + 时间）。 */
fun recordCheckout(context: Context, code: String, at: Long = System.currentTimeMillis()) {
    val clean = code.trim()
    if (clean.isEmpty()) return
    val rest = readEntries(context).filterNot { it.first == clean }
    writeEntries(context, (listOf(clean to at) + rest).take(KEEP))
}

/**
 * 取消某一条「已取件」记录（用户把件标记回未取时调用）。
 *
 * 只删该取件码对应的条目，不动其余 ⇒ 若你之前依次取了 A→B→C：
 * - 取消 A：剩余 [C, B]，起点仍是 C（你还在 C）✅
 * - 取消 C（最近一次）：剩余 [B]，起点回到 B（最近仍完成的那件）✅
 * 避免了「取消标记后灰点/起点仍是旧点」导致排序与导航小窗口对不上（用户 2026-10-01 反馈）。
 */
fun removeCheckoutEntry(context: Context, code: String) {
    val clean = code.trim()
    if (clean.isEmpty()) return
    val rest = readEntries(context).filterNot { it.first == clean }
    writeEntries(context, rest)
}

/** 最近一次取件的位置；超出时间窗、或那个码定位不了（如纯数字快递柜）⇒ null。 */
fun lastCheckoutOrigin(
    context: Context,
    options: RouteOptions = RouteOptions.DEFAULT,
    windowMs: Long = CHECKOUT_ORIGIN_WINDOW_MS,
    now: Long = System.currentTimeMillis(),
): CheckoutOrigin? {
    val code = pickCheckoutOriginCode(readEntries(context), now, windowMs) ?: return null
    val parsed = parseCompartmentCode(code) ?: return null
    if (parsed.zone == PickupZone.UNKNOWN) return null
    val spot = locate(parsed, options) ?: return null
    return CheckoutOrigin(cell = GridCell(spot.row, spot.col), label = "已取的 $parsed", code = code)
}

fun clearCheckoutOrigin(context: Context) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_CHECKOUT).apply()
}

// ---------------------------------------------------------------- 存储

private fun readEntries(context: Context): List<Pair<String, Long>> {
    val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CHECKOUT, null)
        ?: return emptyList()
    val out = ArrayList<Pair<String, Long>>()
    for (part in raw.split('&')) {
        if (part.isEmpty()) continue
        val i = part.indexOf('=')
        if (i <= 0) continue
        val code = runCatching { URLDecoder.decode(part.substring(0, i), "UTF-8") }.getOrNull() ?: continue
        val at = part.substring(i + 1).toLongOrNull() ?: continue
        out += code to at
    }
    return out
}

private fun writeEntries(context: Context, entries: List<Pair<String, Long>>) {
    val raw = entries.joinToString("&") { (code, at) ->
        URLEncoder.encode(code, "UTF-8") + "=" + at
    }
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_CHECKOUT, raw).apply()
}
