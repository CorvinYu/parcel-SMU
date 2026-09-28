package com.xxxx.parcel.util

import kotlin.math.abs

/**
 * 取件最优路径引擎（纯 Kotlin，无 Android 依赖 —— 便于在 JVM 上跑单元测试）。
 *
 * ## 场地拓扑（据用户 2026-10-01 口述确认的部分）
 *
 * ```
 *            入口
 *             |  ← 向右 4 格瓷砖
 *   ┌─────────┴─────────┐
 *   │  纵向主通道(spine) │        ← 把每排横向货架分成左右两段
 *   └─────────┬─────────┘
 *  A排: 1 2 3 4 ┃ 5 6 7 8 9 10 11 12
 *  B排: 1 2 3 4 ┃ 5 6 7 8 9 10 11 12
 *  C排: 1 2 3 4 ┃ 5 6 7 8 9 10 11 12
 *   ...
 * ```
 *
 * - 排（行）字母由入口向深处递增：A、B、C…，**跳过 J 与 S**（特殊区）。
 * - 每排固定 12 个货架：纵向通道**左侧 1~4**、**右侧 5~12**。
 * - 货格号形如 `D5-23` = D 排、第 5 个货架、第 23 个格子。
 * - 每个货架内部格子编号：每行**都从左到右**（第 1 行 1,2,3,4…，第 2 行接着 5,6,7,8…）。
 *
 * ## 为什么用「精确 DP」而不是贪心
 *
 * 走行图可以看成「纵向主通道 + 每排横向支路」，并且**同一排同侧的两点可直接沿排走**
 * （不必绕回主通道）。上述距离是该图上的最短路径，满足度量公理（对称 + 三角不等式），
 * 因此「每件恰好访问一次」的最短路线问题可以用 Held–Karp 子集 DP 求**精确最优**
 * （包裹数个位数时毫秒级），无需最近邻这类近似。DP 结果通过与暴力枚举对比做单元测试验证
 * （见 `PickupRouteTest`）。
 *
 * ⚠️ 尚未确认、因此**做成配置项**（不臆想）：横排字母到哪个字母为止、J 区/S 区/M 区
 * 各自的编号规则、瓷砖实际边长。
 */

/** 一个货格号，形如 `D5-23`。`cellNumber` 可为空（只写了 `D5`）。 */
data class CompartmentCode(
    val rowLetter: Char,
    val shelfNumber: Int,
    val cellNumber: Int?,
    val raw: String,
) {
    override fun toString(): String =
        if (cellNumber == null) "$rowLetter$shelfNumber" else "$rowLetter$shelfNumber-$cellNumber"
}

/**
 * 场地布局参数。**除默认值外都应视为待用户现场核验的可编辑配置**，不要硬编码进逻辑。
 *
 * @param rowLetters     由入口向深处排列的普通排字母（**不含**特殊区）
 * @param shelvesPerRow  每排货架数（默认 12）
 * @param leftBlockEnd   纵向通道左侧的货架号上界（默认 4 ⇒ 左 1~4、右 5~12）
 * @param corridorOffsetTiles 入口到纵向主通道的横向距离（用户口述「4 块瓷砖」）
 * @param rowSpacingTiles     相邻两排之间的纵向距离（瓷砖数）
 * @param specialZoneLetters  特殊区字母：**暂不参与货架路径**，但会单独回报
 *
 * ## 默认值的证据（2026-10-01 用户真实短信样例）
 *
 * 观测到的取件码：`B4-18` `D8-6` `F12-32` `F7-24` `Q12-25` `D3-24` `M5-5` `J5-21`
 * `E9-9` `F2-5` `F11-12` `Q11-27`，另有 `S3-2-2628`（顺丰，三段式）、
 * `Y5-7-1`（**大物区**，三段式）、纯数字如 `54018314`（快递柜）。
 *
 * ⇒ **M 是普通排**（用户 2026-10-01 更正：大物是 **Y** 而不是 M，之前记错了）。
 * ⇒ 字母观测范围 B~Q，故默认取 A~Q 去掉特殊区；**实际范围仍需用户现场核实**。
 * ⇒ J 用户此前描述为「最里面那排左侧的特殊区」，故默认仍按特殊区处理、单独回报，
 *    待用户确认其物理位置后再决定是否并入排序列。
 */
data class SiteLayout(
    val rowLetters: List<Char>,
    val shelvesPerRow: Int = 12,
    val leftBlockEnd: Int = 4,
    val corridorOffsetTiles: Int = 4,
    val rowSpacingTiles: Int = 1,
    val specialZoneLetters: Set<Char> = setOf('J', 'S', 'Y'),
) {
    companion object {
        /** 由入口向深处的普通排（默认值待现场核实，界面可编辑）。 */
        val DEFAULT_SPECIAL_ZONES: Set<Char> = setOf('J', 'S', 'Y')

        fun default(): SiteLayout = SiteLayout(
            rowLetters = ('A'..'Q').filter { it !in DEFAULT_SPECIAL_ZONES }
        )
    }
}

/** 货架在场地中的位置。 */
data class ShelfLocation(
    val rowIndex: Int,
    /** 从纵向主通道横向走到该货架所需的瓷砖格数（≥1） */
    val lateralTiles: Int,
    val onLeft: Boolean,
    val code: CompartmentCode,
)

/**
 * 解析货格号。容忍：大小写、空格、半角/全角/长短横线。
 *
 * 明确拒绝有歧义的写法（如 `D523`）——宁可让用户手动确认，也不猜。
 */
fun parseCompartmentCode(raw: String): CompartmentCode? {
    val text = raw.trim().uppercase()
    if (text.isEmpty()) return null
    val match = Regex("""^([A-Z])\s*(\d{1,2})(?:\s*[-－–—]\s*(\d{1,3}))?$""").find(text) ?: return null
    val row = match.groupValues[1].firstOrNull() ?: return null
    val shelf = match.groupValues[2].toIntOrNull() ?: return null
    val cell = match.groupValues[3].takeIf { it.isNotEmpty() }?.toIntOrNull()
    return CompartmentCode(row, shelf, cell, raw.trim())
}

/** 货架号 → 距纵向主通道的横向格数；越靠近通道越小。 */
fun lateralTilesFor(shelfNumber: Int, layout: SiteLayout): Int? {
    if (shelfNumber < 1 || shelfNumber > layout.shelvesPerRow) return null
    return if (shelfNumber <= layout.leftBlockEnd) {
        // 左侧：通道在左段的右端 ⇒ 4 号最近（1 格），1 号最远
        layout.leftBlockEnd - shelfNumber + 1
    } else {
        // 右侧：通道在右段的左端 ⇒ 5 号最近（1 格），12 号最远
        shelfNumber - layout.leftBlockEnd
    }
}

/** 把货格号定位到场地坐标；特殊区（J/S/M）或未知字母返回 null。 */
fun locate(code: CompartmentCode, layout: SiteLayout): ShelfLocation? {
    if (code.rowLetter in layout.specialZoneLetters) return null
    val rowIndex = layout.rowLetters.indexOf(code.rowLetter)
    if (rowIndex < 0) return null
    val lateral = lateralTilesFor(code.shelfNumber, layout) ?: return null
    return ShelfLocation(
        rowIndex = rowIndex,
        lateralTiles = lateral,
        onLeft = code.shelfNumber <= layout.leftBlockEnd,
        code = code,
    )
}

/** 入口 → 货架 的步数。 */
fun entranceToTiles(target: ShelfLocation, layout: SiteLayout): Int =
    layout.corridorOffsetTiles +
        layout.rowSpacingTiles * target.rowIndex +
        target.lateralTiles

/** 货架 → 出口（同一入口） 的步数。 */
fun exitFromTiles(target: ShelfLocation, layout: SiteLayout): Int =
    target.lateralTiles +
        layout.rowSpacingTiles * target.rowIndex +
        layout.corridorOffsetTiles

/** 两个货架之间的步数（场地最短路径）。 */
fun walkTiles(a: ShelfLocation, b: ShelfLocation, layout: SiteLayout): Int {
    if (a.rowIndex == b.rowIndex) {
        // 同一排：同侧可直接沿着这一排的通道走；异侧必须绕经纵向主通道。
        return if (a.onLeft == b.onLeft) {
            abs(a.lateralTiles - b.lateralTiles)
        } else {
            a.lateralTiles + b.lateralTiles
        }
    }
    // 不同排：必须回到纵向主通道，纵向走 |Δ排|，再横向走到目标。
    return a.lateralTiles +
        layout.rowSpacingTiles * abs(a.rowIndex - b.rowIndex) +
        b.lateralTiles
}

/** 规划结果。 */
data class PickupRoute(
    /** 建议的取件顺序（已定位成功的部分） */
    val orderedCodes: List<CompartmentCode>,
    /** 总步数（瓷砖格） */
    val totalTiles: Int,
    /** 每一段的步数：第 0 段为「入口 → 第 1 件」，之后逐件；若折返入口则最后一段为「末件 → 入口」 */
    val legTiles: List<Int>,
    /** 特殊区货格号（J / S / Y 等，布局尚未确认，**不参与路径规划**，单独列出以免用户以为丢了） */
    val specialZoneCodes: List<String>,
    /** 纯数字取件码 = 快递柜，不在人工货架路径上 */
    val lockerCodes: List<String>,
    /** 完全无法识别的原文 */
    val unresolved: List<String>,
    /** 是否为精确最优（false 表示件数过多，退化为启发式） */
    val exact: Boolean,
) {
    val resolvedCount: Int get() = orderedCodes.size
}

/** 该取件码是否只是数字（快递柜）。 */
fun isLockerCode(raw: String): Boolean {
    val text = raw.trim()
    return text.isNotEmpty() && text.all { it.isDigit() }
}

/** 超过这个件数就不用 O(2^n·n^2) 的精确 DP，退化为最近邻 + 2-opt。 */
const val MAX_EXACT_ITEMS = 13

/**
 * 规划取件路径。
 *
 * @param rawCodes         货格号原文列表（通常来自短信解析出的 compartmentNumber）
 * @param layout           场地布局参数
 * @param returnToEntrance 取完后是否回到入口。为 false 时是「敞开路径」，
 *                         最优解会**把最远的点排在最后**，这也是顺序真正起作用的场景。
 */
fun planPickupRoute(
    rawCodes: List<String>,
    layout: SiteLayout = SiteLayout.default(),
    returnToEntrance: Boolean = true,
): PickupRoute {
    val unresolved = mutableListOf<String>()
    val specialZones = mutableListOf<String>()
    val lockers = mutableListOf<String>()
    val located = mutableListOf<ShelfLocation>()
    for (raw in rawCodes) {
        if (isLockerCode(raw)) {
            // 纯数字 ⇒ 快递柜，不在人工货架路径上
            lockers += raw.trim()
            continue
        }
        val firstLetter = raw.trim().uppercase().firstOrNull()
        if (firstLetter != null && firstLetter in layout.specialZoneLetters) {
            // J / S / Y 等特殊区：编号规则与普通排不同，布局未确认前不臆测
            specialZones += raw
            continue
        }
        val code = parseCompartmentCode(raw)
        if (code == null) {
            unresolved += raw
            continue
        }
        val loc = locate(code, layout)
        if (loc == null) {
            unresolved += raw
            continue
        }
        located += loc
    }

    if (located.isEmpty()) {
        return PickupRoute(
            orderedCodes = emptyList(),
            totalTiles = 0,
            legTiles = emptyList(),
            specialZoneCodes = specialZones,
            lockerCodes = lockers,
            unresolved = unresolved,
            exact = true,
        )
    }

    val useExact = located.size <= MAX_EXACT_ITEMS
    val order = if (useExact) {
        solveExact(located, layout, returnToEntrance)
    } else {
        solveHeuristic(located, layout, returnToEntrance)
    }

    val legs = mutableListOf<Int>()
    legs += entranceToTiles(order.first(), layout)
    for (i in 0 until order.size - 1) {
        legs += walkTiles(order[i], order[i + 1], layout)
    }
    if (returnToEntrance) {
        legs += exitFromTiles(order.last(), layout)
    }

    return PickupRoute(
        orderedCodes = order.map { it.code },
        totalTiles = legs.sum(),
        legTiles = legs,
        specialZoneCodes = specialZones,
        lockerCodes = lockers,
        unresolved = unresolved,
        exact = useExact,
    )
}

// ===== 精确求解（Held–Karp 子集 DP）=====

private fun solveExact(
    targets: List<ShelfLocation>,
    layout: SiteLayout,
    returnToEntrance: Boolean,
): List<ShelfLocation> {
    val n = targets.size
    val full = 1 shl n

    // costFromEntrance[i]，以及 targets 之间的 cost[i][j]
    val fromEntrance = IntArray(n) { entranceToTiles(targets[it], layout) }
    val toExit = IntArray(n) { exitFromTiles(targets[it], layout) }
    val between = Array(n) { i -> IntArray(n) { j -> walkTiles(targets[i], targets[j], layout) } }

    val inf = Int.MAX_VALUE / 4
    // dp[mask][last] = 从入口出发、已访问 mask、最后停在 last 的最小步数
    val dp = Array(full) { IntArray(n) { inf } }
    val parent = Array(full) { IntArray(n) { -1 } }

    for (i in 0 until n) {
        dp[1 shl i][i] = fromEntrance[i]
    }

    for (mask in 1 until full) {
        for (last in 0 until n) {
            val cur = dp[mask][last]
            if (cur >= inf) continue
            for (next in 0 until n) {
                if (mask and (1 shl next) != 0) continue
                val nextMask = mask or (1 shl next)
                val candidate = cur + between[last][next]
                if (candidate < dp[nextMask][next]) {
                    dp[nextMask][next] = candidate
                    parent[nextMask][next] = last
                }
            }
        }
    }

    var bestCost = inf
    var bestLast = 0
    for (last in 0 until n) {
        val finish = dp[full - 1][last] + if (returnToEntrance) toExit[last] else 0
        if (finish < bestCost) {
            bestCost = finish
            bestLast = last
        }
    }

    // 回溯顺序
    val reversed = ArrayList<Int>(n)
    var mask = full - 1
    var cur = bestLast
    while (cur >= 0) {
        reversed += cur
        val prev = parent[mask][cur]
        mask = mask and (1 shl cur).inv()
        cur = prev
    }
    reversed.reverse()
    return reversed.map { targets[it] }
}

// ===== 启发式兜底（件数过多时）=====

private fun solveHeuristic(
    targets: List<ShelfLocation>,
    layout: SiteLayout,
    returnToEntrance: Boolean,
): List<ShelfLocation> {
    val n = targets.size
    val remaining = targets.indices.toMutableList()
    val order = ArrayList<Int>(n)

    // 最近邻
    var current: ShelfLocation? = null
    while (remaining.isNotEmpty()) {
        val pick = remaining.minBy { idx ->
            val t = targets[idx]
            if (current == null) entranceToTiles(t, layout) else walkTiles(current, t, layout)
        }
        order += pick
        remaining.remove(pick)
        current = targets[pick]
    }

    // 2-opt 改良
    fun totalTiles(seq: List<Int>): Int {
        var sum = entranceToTiles(targets[seq.first()], layout)
        for (i in 0 until seq.size - 1) sum += walkTiles(targets[seq[i]], targets[seq[i + 1]], layout)
        if (returnToEntrance) sum += exitFromTiles(targets[seq.last()], layout)
        return sum
    }

    var improved = true
    var best = order.toList()
    var bestCost = totalTiles(best)
    while (improved) {
        improved = false
        outer@ for (i in 0 until n - 1) {
            for (j in i + 2 until n) {
                val candidate = best.toMutableList()
                candidate.subList(i + 1, j + 1).reverse()
                val cost = totalTiles(candidate)
                if (cost < bestCost) {
                    best = candidate
                    bestCost = cost
                    improved = true
                    break@outer
                }
            }
        }
    }
    return best.map { targets[it] }
}
