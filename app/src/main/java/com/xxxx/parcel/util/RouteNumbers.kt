package com.xxxx.parcel.util

import android.content.Context
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * **稳定件号**（首页「快递站」页的 ①②③）。
 *
 * 用户 2026-10-01 明确的两条规则：
 * 1. **隐藏已取件**（`showCompleted = false`）：取完就消失、序号从 1 重新排 —— 保持原样即可。
 * 2. **不隐藏已取件**：取完的那条**留在原地、保留原来的序号**；
 *    不能「消失并跑到最末尾」（那是件号被清空后按 `Int.MAX_VALUE` 排序的副作用）。
 *
 * ⇒ 件号是**稳定编号**，不是「当前访问顺序」：地址一旦拿到号就保留，直到它从列表里消失（号被释放）。
 *    新地址拿**最小空闲号**，未取件的按最优取件顺序优先分配。
 */
private const val PREFS = "parcel_prefs"
private const val KEY_NUMBERS = "route_stable_numbers"

/** 读出上次的件号表（地址 → 件号）。 */
fun loadStableNumbers(context: Context): MutableMap<String, Int> {
    val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_NUMBERS, null)
        ?: return mutableMapOf()
    val out = mutableMapOf<String, Int>()
    for (part in raw.split('&')) {
        if (part.isEmpty()) continue
        val i = part.indexOf('=')
        if (i <= 0) continue
        val key = runCatching { URLDecoder.decode(part.substring(0, i), "UTF-8") }.getOrNull() ?: continue
        val value = part.substring(i + 1).toIntOrNull() ?: continue
        out[key] = value
    }
    return out
}

fun saveStableNumbers(context: Context, numbers: Map<String, Int>) {
    val raw = numbers.entries.joinToString("&") { (key, value) ->
        URLEncoder.encode(key, "UTF-8") + "=" + value
    }
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        .putString(KEY_NUMBERS, raw)
        .apply()
}

/**
 * 计算当前该显示的件号。
 *
 * @param shown      当前列表里出现的地址（**含已取件**，只要它还在列表里）
 * @param pendingInOrder 未取件的地址，按最优取件顺序
 * @param sticky     上次的件号表；**会被就地清理**：不再出现的地址释放它的号
 * @return 地址 → 件号（只含 [shown] 里的地址）
 */
fun assignStableNumbers(
    shown: List<String>,
    pendingInOrder: List<String>,
    sticky: MutableMap<String, Int>,
): Map<String, Int> {
    val shownSet = shown.toSet()
    sticky.keys.retainAll(shownSet)

    val used = sticky.values.toMutableSet()
    fun nextFree(): Int {
        var n = 1
        while (n in used) n++
        return n
    }

    // 未取件的按取件顺序先拿号（新来的件填已取件释放出来的空号）
    for (address in pendingInOrder) {
        if (sticky.containsKey(address)) continue
        val n = nextFree()
        sticky[address] = n
        used += n
    }
    // 其余（通常是已取件、但还没号的历史数据）按列表顺序补号
    for (address in shown) {
        if (sticky.containsKey(address)) continue
        val n = nextFree()
        sticky[address] = n
        used += n
    }
    return shown.associateWith { sticky.getValue(it) }
}

/**
 * 紧凑编号（**隐藏已取件**时用）：清空旧号，未取件按取件顺序从 1 连续编号。
 * 用户明确说这种情形下「重新从 1 开始没问题」。
 */
fun compactNumbers(pendingInOrder: List<String>, sticky: MutableMap<String, Int>): Map<String, Int> {
    sticky.clear()
    return pendingInOrder.mapIndexed { i, address -> address to i + 1 }.toMap()
}
