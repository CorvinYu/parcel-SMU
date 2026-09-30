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
private const val KEY_SF_DONE = "sf_checkout_done_at"
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

/**
 * 时间窗内的「已取件」记录（取件码 → 时间），最近的在前。
 *
 * 用途（用户 2026-10-01）：「已经取了顺丰件」这件事**不能只看当前路线** ——
 * 把 S 件的取件码标记为已取之后，路线里就没有 S 件了，`hasSfCheckout` 会立刻变 false，
 * 于是「顺丰出库」这一步凭空消失（用户实测反馈）。判断「本次行程取过顺丰件」要读这里的记录：
 * 只要窗口内取过 S 区的件，就一直保留「顺丰专用闸机出库」这一步，直到超出时间窗。
 */
fun recentCheckoutEntries(
    context: Context,
    windowMs: Long = CHECKOUT_ORIGIN_WINDOW_MS,
    now: Long = System.currentTimeMillis(),
): List<Pair<String, Long>> =
    readEntries(context).filter { now - it.second in 0..windowMs }

/**
 * 纯逻辑：这批已取件记录里是否有**顺丰（S 区）**的件。
 *
 * 用户 2026-10-01 实测的回归就是它：把 S 件的取件码标记为已取之后，路线里没有 S 件了
 * （`hasSfCheckout` 变 false），「顺丰出库」这一步立刻消失 —— 可是人还没去闸机出库。
 * 所以改用「本次行程（时间窗内）取过 S 件」来判断。
 */
fun containsSfCheckout(entries: List<Pair<String, Long>>): Boolean =
    entries.any { (code, _) -> parseCompartmentCode(code)?.zone == PickupZone.SF }

/** 窗口内是否取过顺丰（S 区）的件 —— 顺丰出库步骤据此保持显示。 */
fun hasRecentSfCheckout(
    context: Context,
    windowMs: Long = CHECKOUT_ORIGIN_WINDOW_MS,
    now: Long = System.currentTimeMillis(),
): Boolean = containsSfCheckout(recentCheckoutEntries(context, windowMs, now))

/**
 * 纯逻辑：这批已取件记录里**需要出库的顺丰包裹数**（去重后的 S 区件数）。
 *
 * 用途（用户 2026-10-01）：「顺丰出库」卡片右侧提醒还有几件顺丰要出库（**测试功能、默认关闭**）。
 * 只要在时间窗内取过 S 区的件，就说明这些件还没走完出库流程。
 */
fun countSfCheckouts(entries: List<Pair<String, Long>>): Int =
    entries.map { it.first }
        .distinct()
        .count { parseCompartmentCode(it)?.zone == PickupZone.SF }

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

// ---------------------------------------------------------------- 顺丰「已出库」

/**
 * 标记「顺丰已出库」——用户 2026-10-01：**点一下「顺丰出库」卡片就代表已经出库**。
 *
 * 语义：没标记时，路线会**动态**把顺丰出库点安排在「所有 S 件之后」的合法位置里最优的一处，
 * 并且**保底排在最终出站之前**；点了之后路线就直接取件 → 出站，不再绕出库机。
 * 存时间戳 ⇒ 与「同一场次」窗口一致（隔夜自动失效）。
 */
fun markSfCheckoutDone(context: Context, at: Long = System.currentTimeMillis()) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_SF_DONE, at).apply()
}

fun clearSfCheckoutDone(context: Context) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_SF_DONE).apply()
}

/** 纯逻辑：标记时间是否还在窗口内（`markedAt == 0` = 从没标记过）。 */
fun isSfDoneFresh(
    markedAt: Long,
    now: Long,
    windowMs: Long = CHECKOUT_ORIGIN_WINDOW_MS,
): Boolean = markedAt > 0 && now - markedAt in 0..windowMs

/** 本次行程是否已标记「顺丰已出库」。 */
fun isSfCheckoutDone(
    context: Context,
    windowMs: Long = CHECKOUT_ORIGIN_WINDOW_MS,
    now: Long = System.currentTimeMillis(),
): Boolean = isSfDoneFresh(
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_SF_DONE, 0L),
    now,
    windowMs,
)

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
