package com.xxxx.parcel.util

import kotlin.math.abs

/**
 * 取件最优路径引擎（纯 Kotlin，无 Android 依赖 —— 可在 JVM 单测里跑）。
 *
 * ## 模型来源（2026-09-29 换代为**数据驱动**）
 *
 * 场地可走格**完全来自用户 Excel 的填充色**（[SiteData] / [SiteModel]），
 * 不再手写「排字母 / 通道间距 / 经主通道还是经东侧通道」这类假设。
 *   - 3 条纵向干线：西侧（靠闸机，列 F~J）、主通道（列 AI~AN）、东侧（列 CK~CP）
 *   - 9 条横向走廊带 + 北侧 J/S 区通道；闸机带可通行（只用于出行）
 *
 * ## 算法
 *
 * 1. 取件点 → 合并区（等价于「货格号 → 场地坐标」）→ **绕开墙与货架**投影到最近通道格
 * 2. 最短路 = 网格 BFS（边权恒为 1 单元格 ⇒ BFS 即精确最短路；绕哪条干线自动得出）
 * 3. 排序 = **Held–Karp 子集 DP**（精确最优；>13 件退化为分块最近邻 + 2-opt 并如实标注）
 *
 * ## 顺丰规则（用户 2026-09-29 两条）
 *
 * - 「只要拿了 S，一定要先从顺丰专用闸机**出库**；若同时还有普通件，可以在顺丰出库后再去普通货架，
 *   最后从普通闸机**出库 + 出站**直接走人。」
 * - 「**顺丰的出库机不能出站**，所以要走那个出站机出站」⇒ **出库 ≠ 出站**：
 *   - 有普通件 → 终点 = `7个普通闸机`（出库 + 出站）
 *   - 只有顺丰件 → `顺丰专用闸机`出库 → `顺丰和无快递出口`出站
 *
 * ⇒ DP 状态 = `(已取件集合, 当前节点, 是否已出库)`；转到出库点的前提是「所有 S 件都已取」。
 */
// ============================================================================
// 货格号 / 分区 / 分类（与上游解析器配合的部分）
// ============================================================================

/** 一个货格号，形如 `D5-23`；顺丰/大件可能是三段（`S3-2-2628`、`Y8-1-3`）。 */
data class CompartmentCode(
    val rowLetter: Char,
    val shelfNumber: Int,
    val cellNumber: Int?,
    val subNumber: Int? = null,
    val raw: String,
) {
    val zone: PickupZone get() = PickupZone.of(rowLetter)

    override fun toString(): String {
        val sb = StringBuilder().append(rowLetter).append(shelfNumber)
        if (cellNumber != null) sb.append('-').append(cellNumber)
        if (subNumber != null) sb.append('-').append(subNumber)
        return sb.toString()
    }
}

/** 取件码所属分区。 */
enum class PickupZone(val label: String) {
    MAIN("主货架区"),
    J_CABINET("J 柜列区"),
    SF("顺丰 S 区"),
    BULK("大件 Y 区"),
    UNKNOWN("未知区");

    companion object {
        fun of(letter: Char): PickupZone = when (letter.uppercaseChar()) {
            'J' -> J_CABINET
            'S' -> SF
            'Y' -> BULK
            in 'A'..'Z' -> MAIN
            else -> UNKNOWN
        }
    }
}

/**
 * 解析货格号。容忍大小写、空格、半角/全角/长短横线，并支持三段式（顺丰 / 大件）。
 *
 * 明确拒绝有歧义的写法（如 `D523`）——宁可让用户手动确认，也不猜。
 */
fun parseCompartmentCode(raw: String): CompartmentCode? {
    val text = raw.trim().uppercase()
    if (text.isEmpty()) return null
    val match = Regex(
        """^([A-Z])\s*(\d{1,2})(?:\s*[-－–—]\s*(\d{1,3}))?(?:\s*[-－–—]\s*(\d{1,4}))?$"""
    ).find(text) ?: return null
    val row = match.groupValues[1].firstOrNull() ?: return null
    val shelf = match.groupValues[2].toIntOrNull() ?: return null
    val cell = match.groupValues[3].takeIf { it.isNotEmpty() }?.toIntOrNull()
    val sub = match.groupValues[4].takeIf { it.isNotEmpty() }?.toIntOrNull()
    return CompartmentCode(row, shelf, cell, sub, raw.trim())
}

private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

/**
 * 从「取件码」文本里识别**人工货架 / 顺丰 / 大件**的货格号（兜底）。
 *
 * 上游 `SmsParser` 的 `compartmentNumber` 只从「格口」「N号柜」这类**快递柜**写法里提取，
 * 人工货架短信（`请用D8-6到人工货架取包裹`）的货格号一直只被当成取件码存进 `code`。
 * 这里做兜底：**取件码本身就是货格号时，把它也当作货格号**。
 *
 * 只认「排字母 + 货架号(-格号)」，因此不会误伤快递柜与其他编号。
 */
fun compartmentFromPickupCode(rawCode: String): String? {
    val text = rawCode.trim()
    if (text.isEmpty()) return null
    for (token in text.split(',', '，', '、', ' ', '\n', '\t')) {
        val t = token.trim()
        if (t.isEmpty()) continue
        if (!t.first().isAsciiLetter()) continue
        if (parseCompartmentCode(t) == null) continue
        return t.uppercase()
    }
    return null
}

/**
 * 一个件的**有效货格号**：优先用短信解析出的货格号；为空时退回「取件码本身就是货格号」。
 */
fun effectiveCompartmentNumber(compartmentNumber: String, code: String): String =
    compartmentNumber.trim().ifBlank { compartmentFromPickupCode(code) ?: "" }

/**
 * 一个地址分组参与路线规划的**锚点货格号**。
 *
 * 入参只放**尚未取件**的短信（`compartmentNumber to code`）。全取完 ⇒ 传空列表 ⇒ 返回 null，
 * 该地址必须从路线与地图上消失。
 *
 * 🔴 用户 2026-10-01 反馈：标记已取件后地图不同步。根因就是这里以前兜底取了「第一条短信（含已取件）」，
 * 于是已取完的地址仍留在顺序里。**已取件的一律不参与规划**。
 */
fun routeAnchorCode(uncompleted: List<Pair<String, String>>): String? {
    val first = uncompleted.firstOrNull() ?: return null
    val code = effectiveCompartmentNumber(first.first, first.second)
    return code.ifBlank { null }
}

/** 该取件码是否只是数字（快递柜）。 */
fun isLockerCode(raw: String): Boolean {
    val text = raw.trim()
    return text.isNotEmpty() && text.all { it.isDigit() }
}

/**
 * 取件地点类型。
 * - **快递站**：取件码含字母 —— `D8-6`（人工货架）、`S3-2-2628`（顺丰）、`Y5-7-1`（大件）
 * - **快递柜**：取件码是纯数字 —— `54018314`、`69824579`
 */
enum class PickupPlace { STATION, LOCKER }

fun classifyPickupPlace(code: String): PickupPlace =
    if (isLockerCode(code)) PickupPlace.LOCKER else PickupPlace.STATION

/** 列表三大类（用户指定：快递站 / 快递柜 / 校外）。 */
enum class PickupCategory(val label: String) {
    STATION("快递站"),
    LOCKER("快递柜"),
    OFF_CAMPUS("校外"),
}

/**
 * 三大类判定（判据依用户真实样例）：
 * 1. 短信**正文**里出现「海事大学」⇒ 校内；否则 ⇒ 校外
 * 2. 校内 且 取件码为纯数字 ⇒ 快递柜
 * 3. 校内 且 取件码含字母 ⇒ 快递站（含顺丰 S、大件 Y、J 柜列）
 *
 * ⚠️ 必须用短信正文而不是解析后的地址（`D8-6` 那条的解析地址里没有「海事大学」）。
 */
fun classifyPickupCategory(code: String, smsBody: String): PickupCategory {
    if (!smsBody.contains("海事大学")) return PickupCategory.OFF_CAMPUS
    return if (isLockerCode(code)) PickupCategory.LOCKER else PickupCategory.STATION
}

// ============================================================================
// 场地参数 / 定位结果
// ============================================================================

/**
 * 场地参数。**新模型是数据驱动的**，所以只剩一个仍属「未实测假设」的旋钮。
 */
data class RouteOptions(
    /**
     * **J 柜列每列格数**。
     *
     * `货位清单` 里 Q1b/Q5 标着「每列实际格数与北端行号待确认」，实测只到 `j5-21`（⇒ ≥21 格）。
     * 这里按该值把柜列纵深等比铺开：格子 1 在**靠通道的外端**，越往里越大。
     */
    val jCellsPerColumn: Int = 21,
) {
    companion object {
        val DEFAULT = RouteOptions()
    }
}

/** 定位好的取件点。 */
data class PickupSpot(
    val code: CompartmentCode,
    /** 精确格位的瓷砖坐标（J 柜列会落在柜列纵深里；普通货架取合并区中心） */
    val lat: Double,
    val depth: Double,
    /** 投影到的通道格（绕开墙与货架） */
    val row: Int,
    val col: Int,
    /** 区内走位（瓷砖）：从通道格走进货架/柜列的那一小段；只有 J 柜列非 0 */
    val stubTiles: Double,
    /** 该区在精确版 Excel 里是一整块 ⇒ 只能定位到最近通道点（Y 区） */
    val approximate: Boolean,
    val label: String,
) {
    val zone: PickupZone get() = code.zone
}

/** 出站方式。 */
enum class RouteExit(val label: String) {
    /** 普通件／混合件：`7个普通闸机` —— 出库 + 出站 */
    NORMAL_GATE("7个普通闸机（出库 + 出站）"),

    /** 只有顺丰件：`顺丰专用闸机`出库**不能出站** ⇒ 还要走到 `顺丰和无快递出口` 出站 */
    SF_EXIT("顺丰和无快递出口（出站）"),
}

/** 一段路线的性质（与 HTML 侧 `legs[].kind` 一一对应）。 */
enum class LegKind {
    /** 入口闸机 → 第一件 */
    ENTRANCE,

    /** 件 → 件 */
    PICK,

    /** 件 → 顺丰专用闸机（**出库**，不是出站） */
    SF_CHECKOUT,

    /** → 出站口（终点；普通闸机或顺丰侧出口） */
    EXIT,
}

/**
 * 一段路线的几何：从 `from` 走到 `to`。
 *
 * 🔴 **单位铁律**：`cells` 是网格**单元格**序列，`stub*Tiles` 是区内走位（**瓷砖**，1 瓷砖 = 2 单元格）。
 * 两者必须分开记账 —— 恒等式（单测钉死）：
 * ```
 * tiles == stubFromTiles + (cells.size - 1) * 0.5 + stubToTiles
 * ```
 * 历史上「stub 只算 1 次」那类 bug 就是这条不成立暴露出来的。
 */
data class RouteLeg(
    val from: String,
    val to: String,
    val kind: LegKind,
    /**
     * BFS 回溯出的通道格序列（每步相邻 ⇒ 结构上不可能穿货架、不可能斜穿）。
     * 同一格（零步）时只有一个元素；目标不可达时为空列表（界面如实说明，不编指引）。
     */
    val cells: List<GridCell>,
    /** 段长（瓷砖，含两端区内走位） */
    val tiles: Double,
    /** 从上一件货架/柜列走回通道的那一段（瓷砖） */
    val stubFromTiles: Double,
    /** 走进本件货架/柜列的那一段（瓷砖） */
    val stubToTiles: Double,
)

/** 路线上的一个停靠点。 */
sealed interface RouteStop {    /** 取件 */
    data class Pickup(val code: CompartmentCode, val spot: PickupSpot) : RouteStop

    /** 顺丰**出库**（顺丰专用闸机；取过 S 件才会出现） */
    data object SfCheckout : RouteStop

    /** 出站（终点） */
    data class Exit(val kind: RouteExit) : RouteStop
}

/** 规划结果。 */
data class PickupRoute(
    /** 建议的取件顺序 */
    val orderedCodes: List<CompartmentCode>,
    /** 完整停靠序列：取件 / 顺丰出库 / 出站 */
    val stops: List<RouteStop>,
    /** 逐段几何：第 i 段 = 走到第 i 站的到达段（第 0 段从入口算起）；供步行提示与图示使用 */
    val legs: List<RouteLeg>,
    val totalTiles: Double,
    /** 顺丰出库插在第几件之后（-1 = 不需要出库） */
    val sfCheckoutAfter: Int,
    val sfCheckoutCell: GridCell?,
    val exitCell: GridCell?,
    val exit: RouteExit,
    /** 纯数字取件码 = 快递柜，不在人工货架路径上 */
    val lockerCodes: List<String>,
    /** 完全无法定位的原文 */
    val unresolved: List<String>,
    /** 是否为精确最优（false 表示件数过多，退化为分块启发式） */
    val exact: Boolean,
) {
    /** 到每一站的步数（第 0 站从入口算起）；单位：瓷砖 —— 由 [legs] 派生，避免两份数据漂移 */
    val legTiles: List<Double> get() = legs.map { it.tiles }

    val resolvedCount: Int get() = orderedCodes.size

    val hasSfCheckout: Boolean get() = sfCheckoutAfter >= 0

    /** 各分区的件数统计。 */
    fun zoneCounts(): Map<PickupZone, Int> =
        orderedCodes.groupingBy { it.zone }.eachCount()
}

/**
 * 超过这个件数就不用 O(2^n·n·2) 的精确 DP，退化为「S 块 → 出库 → 普通块」的两个种子 +
 * 2-opt（最近邻种子 / 走廊扫描种子，取更优者）。
 *
 * 上限怎么定的：n=16 时 DP 状态 = 2^16 × 17 × 2 ≈ 2.2M（FloatArray 约 9MB）＋ 同规模的前驱数组，
 * 手机上可接受；n=18 起内存翻 4 倍（>70MB）就不合适了。53 件这种量级**不可能**精确求解（2^53）。
 */
const val MAX_EXACT_ITEMS = 16

/**
 * 一组「落在同一个货架」的连续取件点。
 *
 * 用户 2026-10-01：同货架的两件在地图上会互相盖住（①被②盖掉），容易看错 ⇒ **合并成一枚标记**，
 * 标号写成一串（`1·2`），点位上只画一次。
 */
data class StopGroup(
    /** 组内各站在 [PickupRoute.stops] 里的下标（升序） */
    val indexes: List<Int>,
    /** 代表格（取组内第一站的通道格，保证一定落在通道上） */
    val cell: GridCell,
    val isPickup: Boolean,
)

/**
 * 把停靠序列里**连续、且同一个货架**（排字母 + 货架号相同）的取件点合成一组；
 * 顺丰出库 / 出站各自单独成组。
 */
fun groupRouteStops(route: PickupRoute): List<StopGroup> {
    val out = ArrayList<StopGroup>()
    route.stops.forEachIndexed { i, stop ->
        val cell = route.legs.getOrNull(i)?.cells?.lastOrNull() ?: return@forEachIndexed
        if (stop is RouteStop.Pickup) {
            val key = shelfKey(stop.code)
            val prev = out.lastOrNull()
            val prevKey = prev?.takeIf { it.isPickup }
                ?.let { g -> (route.stops.getOrNull(g.indexes.last()) as? RouteStop.Pickup)?.let { shelfKey(it.code) } }
            if (prev != null && prev.isPickup && prevKey == key) {
                out[out.size - 1] = prev.copy(indexes = prev.indexes + i)
            } else {
                out += StopGroup(listOf(i), cell, isPickup = true)
            }
        } else {
            out += StopGroup(listOf(i), cell, isPickup = false)
        }
    }
    return out
}

/** 货架标识：`S3-2-2628` 与 `S3-3-7606` 都算 `S3`（同一货架）。 */
private fun shelfKey(code: CompartmentCode): String =
    "${code.rowLetter.uppercaseChar()}${code.shelfNumber}"

/**
 * 场地**入口闸机**投影到的通道格。
 *
 * 地图上的「入口」标志必须固定画在这里 —— 用户 2026-10-01：起点改成「刚取完的那一点」之后，
 * 入口标志跟着起点跑了（看起来像入口被搬走），那是错的。
 */
fun siteEntranceCell(): GridCell? = SiteIndex.entranceCell

// ============================================================================
// 场地索引（合并区 / 闸机带）
// ============================================================================

private object SiteIndex {

    /** 一个合并区（同标签的多个矩形取并集后的包围盒）。 */
    class Rect(val label: String, val c0: Int, val c1: Int, val r0: Int, val r1: Int) {
        val lat0: Double get() = SiteModel.latOf(c0) - 0.25
        val lat1: Double get() = SiteModel.latOf(c1) + 0.25
        val d0: Double get() = SiteModel.depthOf(r1) - 0.25      // 南侧（小）
        val d1: Double get() = SiteModel.depthOf(r0) + 0.25      // 北侧（大）
        val centerRow: Double get() = (r0 + r1 + 1) / 2.0
        val centerCol: Double get() = (c0 + c1 + 1) / 2.0
        val cells: List<GridCell>
            get() = buildList {
                for (r in r0..r1) for (c in c0..c1) add(GridCell(r, c))
            }
    }

    val rects: List<Rect> by lazy {
        val out = ArrayList<Rect>(SiteData.rectLabels.size)
        for (i in SiteData.rectLabels.indices) {
            val b = i * 4
            out += Rect(
                SiteData.rectLabels[i].trim().uppercase(),
                SiteData.rectBounds[b], SiteData.rectBounds[b + 1],
                SiteData.rectBounds[b + 2], SiteData.rectBounds[b + 3],
            )
        }
        out
    }

    /** 标签 → 并集包围盒（同标签多个矩形时取并集）。 */
    private val byLabel: Map<String, Rect> by lazy {
        val map = LinkedHashMap<String, Rect>()
        for (r in rects) {
            if (r.label.isEmpty()) continue
            val old = map[r.label]
            map[r.label] = if (old == null) r else Rect(
                r.label,
                minOf(old.c0, r.c0), maxOf(old.c1, r.c1),
                minOf(old.r0, r.r0), maxOf(old.r1, r.r1),
            )
        }
        map
    }

    fun rectForLabel(label: String): Rect? = byLabel[label.uppercase()]

    /**
     * 标签**前缀**回溯：查 `S1` 时并集所有 `S1-*`（`S1-1`…`S1-10`）。
     *
     * 🔴 为什么需要（2026-10-03）：用户 10-02 更新地图后，Excel 里每个货格都被细化编号
     *    （`S1-1`…`S1-10`、`Y5-1`…`Y5-6`、`Y8-2-1`…），**原来那个笼统的 `S1` 标签就不存在了**。
     *    而取件码 `S3-2-2628` 解析出来的货架号只有 `S3` ⇒ `rectForLabel("S3")` 会查不到，
     *    顺丰件直接定位失败。实测：旧图有 `S1`/`S2`/`S3`，新图**全没了**。
     *
     *    这里做前缀兜底：先查精确标签；查不到就取所有以 `label-` 开头的标签的**并集包围盒**。
     *    这样既保留细粒度编号，又不破坏 App 的定位。
     *
     * ⚠️ 只认 `-` 分隔的前缀，避免 `S1` 误匹配 `S10`。
     */
    fun rectForPrefix(label: String): Rect? {
        val key = label.uppercase()
        byLabel[key]?.let { return it }
        val prefix = "$key-"
        var acc: Rect? = null
        for ((k, r) in byLabel) {
            if (!k.startsWith(prefix)) continue
            acc = if (acc == null) r else Rect(
                key,
                minOf(acc.c0, r.c0), maxOf(acc.c1, r.c1),
                minOf(acc.r0, r.r0), maxOf(acc.r1, r.r1),
            )
        }
        return acc
    }

    /** 某个货架子位（`S1-8` / `Y5-3` / `Y8-2-1`）的精确矩形；查不到返回 null。 */
    fun rectForCell(label: String, cell: Int): Rect? =
        byLabel["${label.uppercase()}-$cell"]

    /**
     * 闸机**门口**的通道格（＝站在闸机前的那一格，**在闸机带外面**）。
     *
     * 🔴 用户 2026-10-01 两次反馈「地图上道路和闸机重叠」：停靠点原本取闸机带**内部**的格子
     *    （带中心），画出来就是路线压在闸机带上。改成用带外紧邻的通道格当停靠点 ⇒
     *    路线只走到门口为止，不再进入闸机带。
     */
    private fun gateMouths(rectsIn: List<Rect>): List<GridCell> {
        val out = LinkedHashSet<GridCell>()
        for (rect in rectsIn) {
            for (r in rect.r0..rect.r1) for (c in rect.c0..rect.c1) {
                if (SiteModel.kindAt(r - 1, c) == SiteModel.WALK) out += GridCell(r - 1, c)
                if (SiteModel.kindAt(r + 1, c) == SiteModel.WALK) out += GridCell(r + 1, c)
                if (SiteModel.kindAt(r, c - 1) == SiteModel.WALK) out += GridCell(r, c - 1)
                if (SiteModel.kindAt(r, c + 1) == SiteModel.WALK) out += GridCell(r, c + 1)
            }
        }
        return out.toList()
    }

    /** `7个普通闸机`：普通件出库 + 出站（多格 ⇒ 作为集合就近用） */
    val normalGates: List<GridCell> by lazy {
        gateMouths(rects.filter { it.label.isNotEmpty() && "普通闸机" in it.label })
    }

    /** 顺丰两处：含「专用」的是**出库机**，另一处（`顺丰和无快递出口`）是**出站口**。 */
    private val sfGateRects: List<Rect> by lazy {
        rects.filter { it.label.isNotEmpty() && "顺丰" in it.label && ("闸机" in it.label || "出口" in it.label) }
    }

    private val sfCheckoutRects: List<Rect> by lazy { sfGateRects.filter { "专用" in it.label } }
    private val sfExitRects: List<Rect> by lazy { sfGateRects.filter { "专用" !in it.label } }

    /** 顺丰**出库**节点：必须是单一格（中间停靠点）⇒ 取带外门口里**离带中心最近**的那一格。 */
    val sfCheckoutCell: GridCell? by lazy {
        val mouths = gateMouths(sfCheckoutRects)
        if (mouths.isEmpty() || sfCheckoutRects.isEmpty()) return@lazy null
        val cr = (sfCheckoutRects.minOf { it.r0 } + sfCheckoutRects.maxOf { it.r1 } + 1) / 2.0
        val cc = (sfCheckoutRects.minOf { it.c0 } + sfCheckoutRects.maxOf { it.c1 } + 1) / 2.0
        mouths.minByOrNull {
            val dr = it.row - cr
            val dc = it.col - cc
            dr * dr + dc * dc
        }
    }

    /** 顺丰出站口：带外门口那些格（就近选一个） */
    val sfExitGates: List<GridCell> by lazy { gateMouths(sfExitRects) }

    /**
     * 入口：Excel 里用户单独用另一颜色填的入口闸机（`W59:AB59`，**不是合并区**）
     * → 绕开墙与货架投影到最近通道格。
     */
    val entranceCell: GridCell? by lazy {
        val src = ArrayList<GridCell>()
        var centerRow = 0.0
        var centerCol = 0.0
        var widest = -1
        var i = 0
        while (i < SiteData.entranceSpans.size) {
            val row = SiteData.entranceSpans[i]
            val c0 = SiteData.entranceSpans[i + 1]
            val c1 = SiteData.entranceSpans[i + 2]
            if (c1 - c0 > widest) {          // 入口取最宽的那一段（另一段是顺丰闸机带上的单格标记）
                widest = c1 - c0
                src.clear()
                for (c in c0..c1) src += GridCell(row, c)
                centerRow = row.toDouble()
                centerCol = (c0 + c1 + 1) / 2.0
            }
            i += 3
        }
        if (src.isEmpty()) return@lazy null
        SiteModel.nearestWalkFrom(src, centerRow, centerCol)
    }
}

// ============================================================================
// 定位
// ============================================================================

/**
 * 把货格号定位到场地：
 * 1. 命中合并区，三级优先（2026-10-03 起支持细粒度编号）：
 *    ① **子位精确**：`S1-8` / `Y5-3` / `Y8-2-1` —— 用户 10-02 更新后地图里每个货格都是独立合并区
 *    ② **货架级并集**：`S1` = 所有 `S1-*` 的并集
 *    ③ **区级兜底**：`S区域` / `Y区域`（此时 `approximate = true`）
 * 2. 按区规则取**精确格位**（仅在没有子位精确命中时使用）：
 *    - **S 顺丰**：s1 货位 1~10、s2/s3 各 1~8，**左端为 1 向右递增** ⇒ 横向展开
 *    - **J 柜列**：沿列**由外端（靠通道）向里递增** ⇒ 纵深展开，并把柜列内走位计入距离
 *    - **Y 大件**：货架内按子位 3~4 等比铺开
 * 3. **绕开墙与货架**投影到最近通道格
 *
 * 无法定位（未知字母、货架号越界）返回 null。
 */
fun locate(code: CompartmentCode, options: RouteOptions = RouteOptions.DEFAULT): PickupSpot? {
    val letter = code.rowLetter.uppercaseChar()
    // 各区的货架号范围（越界直接判为无法定位，避免 Y 区整块把 Y9 也算进来）
    val shelfRange = when (letter) {
        'J' -> 1..6
        'S' -> 1..3
        'Y' -> 1..8
        else -> 1..12
    }
    if (code.shelfNumber !in shelfRange) return null
    val shelfLabel = "$letter${code.shelfNumber}"

    // 🔴 定位优先级（2026-10-03 用户 10-02 更新地图后新增细粒度编号）：
    //   ① 子位精确命中：`S1-8` / `Y5-3` / `Y8-2-1`（地图里每个货格都有自己的合并区）
    //   ② 货架级并集：`S1` = 所有 `S1-*` 的并集（旧图是单个大块，新图被拆成多个小块）
    //   ③ 区级兜底：`S区域` / `Y区域`
    //   旧版只做 ②③，新图里 `S1` 这个标签已不存在 ⇒ 顺丰件会定位失败，故必须加 ①。
    val cellRect: SiteIndex.Rect? = code.cellNumber?.let { c ->
        val base = if (code.subNumber != null) "$shelfLabel-$c" else shelfLabel
        val sub = if (code.subNumber != null) code.subNumber!! else c
        SiteIndex.rectForCell(base, sub)
    }
    val shelfRect = SiteIndex.rectForPrefix(shelfLabel)
    val rect = cellRect ?: shelfRect
        ?: SiteIndex.rectForPrefix("${letter}区域")
        ?: return null
    // 只有连「货架级」都查不到时才算近似（子位命中 = 精确）
    val approximate = cellRect == null && shelfRect == null

    var lat = (rect.lat0 + rect.lat1) / 2.0
    var depth = (rect.d0 + rect.d1) / 2.0
    var posNote = ""

    when {
        // ① 子位精确命中 ⇒ 直接用该货格的中心，不再等比铺开
        cellRect != null -> {
            posNote = when (letter) {
                'S' -> "s${code.shelfNumber} 第${code.cellNumber}格（地图精确格位）"
                'J' -> "j${code.shelfNumber} 第${code.cellNumber}格（地图精确格位）"
                'Y' -> "y${code.shelfNumber} 第${code.cellNumber}格（地图精确格位）"
                else -> "$shelfLabel 第${code.cellNumber}格（地图精确格位）"
            }
        }
        letter == 'S' -> {
            val n = if (code.shelfNumber == 1) 10 else 8          // s1 = 10 格，s2/s3 = 8 格
            val k = ((code.cellNumber ?: 1) - 1).coerceIn(0, n - 1)
            lat = rect.lat0 + (if (n > 1) k.toDouble() / (n - 1) else 0.0) * (rect.lat1 - rect.lat0)
            posNote = "s${code.shelfNumber} 第${code.cellNumber ?: 1}格（共 $n 格，左端为 1）"
        }
        letter == 'J' -> {
            val n = options.jCellsPerColumn.coerceAtLeast(1)
            val k = ((code.cellNumber ?: 1) - 1).coerceIn(0, n - 1)
            depth = rect.d0 + (if (n > 1) k.toDouble() / (n - 1) else 0.0) * (rect.d1 - rect.d0)
            posNote = "j${code.shelfNumber} 第${code.cellNumber ?: 1}格（沿列由外端向里，按 $n 格铺开）"
        }
        letter == 'Y' -> {
            // Y 区货架内按子位等比铺开（货架级并集时用；区级兜底时仍走近似）
            val n = if (code.shelfNumber >= 8) 3 else 4
            val k = ((code.cellNumber ?: 1) - 1).coerceIn(0, n - 1)
            lat = rect.lat0 + (if (n > 1) k.toDouble() / (n - 1) else 0.0) * (rect.lat1 - rect.lat0)
            posNote = "y${code.shelfNumber} 第${code.cellNumber ?: 1}格（共 $n 格）"
        }
    }

    // 投影：从整个合并区出发、绕开墙与货架；**决胜基准用按格位算出的精确点**
    // （用合并区中心的话，S 区同一货架的不同格会全投到同一格，段距恒为 0 —— 踩过）
    //
    // 🔴 **J 柜列必须限定从「南侧开口」投影**（CLAUDE.md 平面图铁律第 4 条）：
    //    J 柜列是南北向竖柜、**只在南端开口**。旧图 J 区西侧没有通道，几何最近邻碰巧正确；
    //    但 2026-10-03 用户把 J 区**整体西移**后，柜列东侧紧邻通道（实测 J5 在 W 列、
    //    X 列就是通道），`nearestWalkFrom` 会把 `J5-21` 投到 `(4,24)` = **从北端绕出去**，
    //    相当于"走到柜列背后拿件"。⇒ 这里显式只从「柜列最南一行往南」找开口通道。
    val projCells: List<GridCell>
    if (letter == 'J') {
        val southRow = rect.r1                       // 行号越大越靠南
        val mouth = (rect.c0..rect.c1).mapNotNull { c ->
            SiteModel.kindAt(southRow + 1, c).takeIf { it == SiteModel.WALK }?.let { GridCell(southRow + 1, c) }
        }
        // 开口格存在 ⇒ 只从出口那一行投影；否则退回整块（容错）
        projCells = mouth.ifEmpty { rect.cells }
    } else {
        projCells = rect.cells
    }
    val cell = SiteModel.nearestWalkFrom(projCells, SiteModel.rowOf(depth), SiteModel.colOf(lat))
        ?: return null
    val (cellLat, cellDepth) = SiteModel.centerOf(cell.row, cell.col)
    // 区内走位：
    // - **J 柜列**：必须算「从柜列南侧开口往里走多少」。🔴 不能用 `abs(depth - cellDepth)` ——
    //   柜列两侧都有通道时 BFS 会把不同格投到不同行（J5-1→W12、J5-21→X4），
    //   abs 会让 stub 不再随格位单调递增（实测 0.50→0.30→0.30→0.50，2026-10-03 被单测抓到）。
    //   正确做法：以柜列**南端开口**（rect.d0）为固定基准，stub = 格位到开口的纵深差。
    // - 普通货架只有半块瓷砖深，格位归一点（stub = 0）。
    val stub = if (letter == 'J') (depth - rect.d0).coerceAtLeast(0.0) else 0.0

    val zoneName = when (code.zone) {
        PickupZone.MAIN -> "$letter 排 ${code.shelfNumber} 号货架"
        PickupZone.J_CABINET -> "J 柜列 j${code.shelfNumber}"
        PickupZone.SF -> "顺丰 s${code.shelfNumber}"
        PickupZone.BULK -> "大件 Y 区"
        PickupZone.UNKNOWN -> "$letter${code.shelfNumber}"
    }
    val label = buildString {
        append(zoneName)
        if (code.cellNumber != null) append(" 第").append(code.cellNumber).append("格")
        if (posNote.isNotEmpty()) append("　").append(posNote)
        append("　（通道格 ").append(cell.row).append(',').append(cell.col).append('）')
        if (stub > 0.01) append("　区内走位 ").append(fmt1(stub)).append(" 格")
        if (approximate) append("　⚠ 该区在精确版里未细分，只定位到最近通道点")
    }
    return PickupSpot(
        code = code, lat = lat, depth = depth, row = cell.row, col = cell.col,
        stubTiles = stub, approximate = approximate, label = label,
    )
}

private fun fmt1(value: Double): String {
    val rounded = kotlin.math.round(value * 10.0) / 10.0
    return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString()
    else rounded.toString()
}

// ============================================================================
// 规划（Held–Karp ＋ 顺丰出库先后约束）
// ============================================================================

private const val INF = 1_000_000_000

/** 距离网格（瓷砖 = 单元格 × 0.5） */
private const val CELL = SiteModel.CELL_TILES

private fun bfsDistTo(bfs: SiteModel.Bfs?, row: Int, col: Int): Int {
    if (bfs == null) return INF
    val d = SiteModel.distTo(bfs, row, col)
    return if (d < 0) INF else d
}

private fun minToCells(bfs: SiteModel.Bfs?, cells: List<GridCell>): Int {
    var best = INF
    for (c in cells) {
        val d = bfsDistTo(bfs, c.row, c.col)
        if (d < best) best = d
    }
    return best
}

/** 两组格子之间的最短距离（对 A 逐点 BFS，取到 B 的最近）。 */
private fun minBetweenCells(a: List<GridCell>, b: List<GridCell>): Int {
    var best = INF
    for (x in a) {
        val bfs = SiteModel.bfs(x.row, x.col) ?: continue
        for (y in b) {
            val d = bfsDistTo(bfs, y.row, y.col)
            if (d < best) best = d
        }
    }
    return best
}

/**
 * 规划取件路径。
 *
 * @param rawCodes 货格号原文列表（通常来自短信解析出的 compartmentNumber，或取件码兜底）
 * @param options  场地参数（目前只有 J 每列格数）
 * @param startCell 起点通道格：默认 `null` = 入口闸机；
 *   **刚取完一件时传那一件的通道格** ⇒ 路线从现场接着走，而不是又从入口出发（用户 2026-10-01）
 * @param startLabel 起点在文案里的名字（如「已取的 S3-2-2628」）
 */
fun planPickupRoute(
    rawCodes: List<String>,
    options: RouteOptions = RouteOptions.DEFAULT,
    startCell: GridCell? = null,
    startLabel: String = "入口闸机",
    /**
     * 顺丰是否**已经出库**（用户 2026-10-01：在「顺丰出库」卡片上点一下就代表已出库）。
     * true ⇒ 路线**不再插入顺丰出库节点**（直接取件 → 出站）；
     * false ⇒ 保留出库节点，由 Held–Karp 在「所有 S 件之后」的合法位置里挑最优的一个，
     * 并且**一定排在最终出站之前**（保底）。
     */
    sfCheckedOut: Boolean = false,
): PickupRoute {
    val unresolved = mutableListOf<String>()
    val lockers = mutableListOf<String>()
    val spots = mutableListOf<PickupSpot>()
    for (raw in rawCodes) {
        if (isLockerCode(raw)) {
            lockers += raw.trim()
            continue
        }
        val code = parseCompartmentCode(raw)
        if (code == null || code.zone == PickupZone.UNKNOWN) {
            unresolved += raw
            continue
        }
        val spot = locate(code, options)
        if (spot == null) {
            unresolved += raw
            continue
        }
        spots += spot
    }

    // 起点：默认入口闸机；「刚取完一件」时由调用方传那一件的通道格（路线从现场续走）
    val entrance = startCell ?: SiteIndex.entranceCell
    if (spots.isEmpty() || entrance == null) {
        if (entrance == null && spots.isNotEmpty()) {
            unresolved += spots.map { it.code.raw }
        }
        return PickupRoute(
            orderedCodes = emptyList(), stops = emptyList(), legs = emptyList(), totalTiles = 0.0,
            sfCheckoutAfter = -1, sfCheckoutCell = SiteIndex.sfCheckoutCell, exitCell = null,
            exit = RouteExit.NORMAL_GATE, lockerCodes = lockers, unresolved = unresolved, exact = true,
        )
    }

    val n = spots.size
    val sfIndexes = spots.indices.filter { spots[it].zone == PickupZone.SF }
    val hasSf = sfIndexes.isNotEmpty()
    /** 是否**还需要**去顺丰专用闸机出库：有 S 件 且 用户还没点「已出库」 */
    val needsSfCheckout = hasSf && !sfCheckedOut
    val hasNormal = spots.any { it.zone != PickupZone.SF }
    if (needsSfCheckout && SiteIndex.sfCheckoutCell == null) {
        return PickupRoute(
            orderedCodes = emptyList(), stops = emptyList(), legs = emptyList(), totalTiles = 0.0,
            sfCheckoutAfter = -1, sfCheckoutCell = null, exitCell = null, exit = RouteExit.NORMAL_GATE,
            lockerCodes = lockers, unresolved = (unresolved + spots.map { it.code.raw }).toList(),
            exact = true,
        )
    }
    if (needsSfCheckout && !hasNormal && SiteIndex.sfExitGates.isEmpty()) {
        return PickupRoute(
            orderedCodes = emptyList(), stops = emptyList(), legs = emptyList(), totalTiles = 0.0,
            sfCheckoutAfter = -1, sfCheckoutCell = null, exitCell = null, exit = RouteExit.NORMAL_GATE,
            lockerCodes = lockers, unresolved = (unresolved + spots.map { it.code.raw }).toList(),
            exact = true,
        )
    }

    // 每个取件点 / 入口各做一次 BFS
    val bfs = arrayOfNulls<SiteModel.Bfs>(n + 1)
    bfs[0] = SiteModel.bfs(entrance.row, entrance.col)
    for (i in 0 until n) bfs[i + 1] = SiteModel.bfs(spots[i].row, spots[i].col)

    val stubCells = DoubleArray(n) { spots[it].stubTiles / CELL }
    val fromEntrance = DoubleArray(n) { bfsDistTo(bfs[0], spots[it].row, spots[it].col).toDouble() + stubCells[it] }
    fun pair(i: Int, j: Int): Double =
        stubCells[i] + bfsDistTo(bfs[i + 1], spots[j].row, spots[j].col) + stubCells[j]

    val normalGates = SiteIndex.normalGates
    // 已出库时用不到顺丰出库点 ⇒ 允许为空（不再 `!!`）
    val sfCell = SiteIndex.sfCheckoutCell
    val sfExitGates = SiteIndex.sfExitGates
    val toNormal = DoubleArray(n) { minToCells(bfs[it + 1], normalGates).toDouble() + stubCells[it] }
    val toSf = DoubleArray(n) {
        if (sfCell == null) Double.MAX_VALUE
        else minToCells(bfs[it + 1], listOf(sfCell)).toDouble() + stubCells[it]
    }
    val toSfExit = DoubleArray(n) { minToCells(bfs[it + 1], sfExitGates).toDouble() + stubCells[it] }
    val sfToNormal = if (sfCell == null) Double.MAX_VALUE
    else minBetweenCells(listOf(sfCell), normalGates).toDouble()
    val sfToExit = if (needsSfCheckout && sfCell != null) {
        minBetweenCells(listOf(sfCell), sfExitGates).toDouble()
    } else {
        Double.MAX_VALUE
    }

    /** 需要出库时前面已校验非空；DP 与装配里用到它的分支都只在「需要出库」时才走到。 */
    fun sfPoint(): GridCell = sfCell ?: error("需要顺丰出库时出库点不应为空")

    val exitKind = if (hasNormal) RouteExit.NORMAL_GATE else RouteExit.SF_EXIT

    val seq: List<Int>            // 节点序列：0..n-1 = 取件点，n = 顺丰出库点
    val totalCells: Double
    if (n <= MAX_EXACT_ITEMS) {
        val full = 1 shl n
        val size = full * (n + 1) * 2
        val dp = DoubleArray(size) { Double.MAX_VALUE }
        val par = IntArray(size) { -1 }
        fun id(mask: Int, last: Int, sfDone: Int) = ((mask * (n + 1) + last) shl 1) or sfDone
        for (i in 0 until n) dp[id(1 shl i, i, 0)] = fromEntrance[i]
        for (mask in 1 until full) {
            for (last in 0..n) {
                for (sfDone in 0..1) {
                    val cur = dp[id(mask, last, sfDone)]
                    if (cur == Double.MAX_VALUE) continue
                    if (last == n) {
                        for (x in 0 until n) {
                            if (mask and (1 shl x) != 0) continue
                            val v = cur + bfsDistTo(bfs[x + 1], sfPoint().row, sfPoint().col) + stubCells[x]
                            val t = id(mask or (1 shl x), x, 1)
                            if (v < dp[t]) {
                                dp[t] = v; par[t] = last
                            }
                        }
                    } else {
                        for (x in 0 until n) {
                            if (mask and (1 shl x) != 0) continue
                            val v = cur + pair(last, x)
                            val t = id(mask or (1 shl x), x, sfDone)
                            if (v < dp[t]) {
                                dp[t] = v; par[t] = last
                            }
                        }
                        val allSfTaken = sfIndexes.all { mask and (1 shl it) != 0 }
                        if (needsSfCheckout && sfDone == 0 && allSfTaken) {
                            val v = cur + toSf[last]
                            val t = id(mask, n, 1)
                            if (v < dp[t]) {
                                dp[t] = v; par[t] = last
                            }
                        }
                    }
                }
            }
        }
        var best = Double.MAX_VALUE
        var bestLast = -1
        var bestSf = 0
        for (last in 0..n) {
            for (sfDone in 0..1) {
                val v = dp[id(full - 1, last, sfDone)]
                if (v == Double.MAX_VALUE) continue
                if (needsSfCheckout && sfDone == 0) continue   // 拿了 S 却没出库 ⇒ 非法
                val endCost = when {
                    hasNormal -> if (last == n) sfToNormal else toNormal[last]
                    needsSfCheckout -> {
                        if (last != n) continue              // 只有顺丰件 ⇒ 终点只能在出库之后
                        sfToExit
                    }
                    // 已出库 + 只有顺丰件 ⇒ 直接去顺丰侧出站口
                    else -> toSfExit[last]
                }
                if (v + endCost < best) {
                    best = v + endCost; bestLast = last; bestSf = sfDone
                }
            }
        }
        if (bestLast < 0) {
            return PickupRoute(
                orderedCodes = emptyList(), stops = emptyList(), legs = emptyList(), totalTiles = 0.0,
                sfCheckoutAfter = -1, sfCheckoutCell = sfCell, exitCell = null, exit = exitKind,
                lockerCodes = lockers, unresolved = (unresolved + spots.map { it.code.raw }).toList(),
                exact = true,
            )
        }
        val rev = ArrayList<Int>()
        var mask = full - 1
        var cur = bestLast
        var sfDone = bestSf
        while (cur >= 0) {
            rev.add(cur)
            val p = par[id(mask, cur, sfDone)]
            if (cur == n) sfDone = 0 else mask = mask and (1 shl cur).inv()
            cur = p
        }
        seq = rev.asReversed()
        totalCells = best
    } else {
        // 启发式：**需要出库时** S 件块 → 顺丰出库 → 普通件块；已出库时所有件合成一块走 2-opt
        val sfList = if (needsSfCheckout) sfIndexes.toMutableList() else mutableListOf()
        val normalList = if (needsSfCheckout) {
            spots.indices.filter { spots[it].zone != PickupZone.SF }.toMutableList()
        } else {
            spots.indices.toMutableList()
        }

        fun nn(list: MutableList<Int>, fromEntranceStart: Boolean): MutableList<Int> {
            val left = list.toMutableList()
            val out = mutableListOf<Int>()
            var cu = -1
            while (left.isNotEmpty()) {
                var bi = 0
                var bv = Double.MAX_VALUE
                for (k in left.indices) {
                    val x = left[k]
                    val cand = when {
                        cu >= 0 -> pair(cu, x)
                        fromEntranceStart -> fromEntrance[x]
                        else -> bfsDistTo(bfs[x + 1], sfPoint().row, sfPoint().col) + stubCells[x]
                    }
                    if (cand < bv) {
                        bv = cand; bi = k
                    }
                }
                out += left[bi]
                cu = left[bi]
                left.removeAt(bi)
            }
            return out
        }

        fun seqCost(sfSeq: List<Int>, nSeq: List<Int>): Double {
            var t = 0.0
            var cu = -1
            for (x in sfSeq) {
                t += if (cu < 0) fromEntrance[x] else pair(cu, x)
                cu = x
            }
            if (needsSfCheckout) {
                t += toSf[cu]
                if (nSeq.isNotEmpty()) {
                    t += bfsDistTo(bfs[nSeq[0] + 1], sfPoint().row, sfPoint().col) + stubCells[nSeq[0]]
                    var cu2 = -1
                    for (x in nSeq) {
                        if (cu2 >= 0) t += pair(cu2, x)
                        cu2 = x
                    }
                    t += toNormal[cu2]
                } else {
                    t += sfToExit
                }
            } else {
                var cu3 = -1
                for (x in nSeq) {
                    t += if (cu3 < 0) fromEntrance[x] else pair(cu3, x)
                    cu3 = x
                }
                t += if (hasNormal) toNormal[cu3] else toSfExit[cu3]
            }
            return t
        }

        fun twoOpt(arr: MutableList<Int>, sRef: List<Int>, nRef: List<Int>, isSfBlock: Boolean) {
            var improved = true
            while (improved) {
                improved = false
                for (a in arr.indices) for (b in a + 1 until arr.size) {
                    val cand = arr.toMutableList()
                    cand.subList(a, b + 1).reverse()
                    val oldCost = if (isSfBlock) seqCost(arr, nRef) else seqCost(sRef, arr)
                    val newCost = if (isSfBlock) seqCost(cand, nRef) else seqCost(sRef, cand)
                    if (newCost < oldCost - 1e-9) {
                        arr.clear(); arr.addAll(cand); improved = true
                    }
                }
            }
        }

        val sfSeq = nn(sfList, true)

        // 普通件块用两个种子各跑一遍 2-opt，取更优者：
        //   ① 最近邻（对小规模、聚簇场景好）
        //   ② **走廊扫描**（按投影行从入口一侧往里、同一走廊内按横向走 —— 大件数时更像人走法）
        val seedA = nn(normalList, !needsSfCheckout)
        twoOpt(seedA, sfSeq, normalList, false)

        val seedB = normalList.sortedWith(
            compareByDescending<Int> { spots[it].row }.thenBy { spots[it].col }
        ).toMutableList()
        twoOpt(seedB, sfSeq, normalList, false)

        val nSeq = if (seqCost(sfSeq, seedA) <= seqCost(sfSeq, seedB)) seedA else seedB
        // 再用选定的普通块为参照，把 S 块的先后顺序也 2-opt 一遍
        twoOpt(sfSeq, sfSeq, nSeq, true)
        val built = ArrayList<Int>(n)
        built.addAll(sfSeq)
        if (needsSfCheckout) built.add(n)
        built.addAll(nSeq)
        seq = built
        totalCells = seqCost(sfSeq, nSeq)
    }

    // ---- 组装结果：停靠序列 + 逐段几何（含**通道格序列**，供步行提示与图示使用）----
    val stops = mutableListOf<RouteStop>()
    val legList = mutableListOf<RouteLeg>()
    val orderedCodes = mutableListOf<CompartmentCode>()
    var cursor = -1          // -1 = 入口，-2 = 顺丰出库点，>=0 = 件下标

    /**
     * 顺丰出库点出发的 BFS：出库后继续取件/去出站时回溯格序列用（无向图，距离与反向一致）。
     * **已出库时不存在这个点** ⇒ 为 null（`sourceBfs()` 只在 cursor == -2 时才用它）。
     */
    val bfsSf = sfCell?.let { SiteModel.bfs(it.row, it.col) }

    fun cellsOf(source: SiteModel.Bfs?, row: Int, col: Int): List<GridCell> =
        if (source == null) emptyList() else (SiteModel.path(source, row, col) ?: emptyList())

    /** 当前位置的 BFS 源：-1 入口 / -2 顺丰出库点 / >=0 第 cursor 件 */
    fun sourceBfs(): SiteModel.Bfs? = when {
        cursor == -1 -> bfs[0]
        cursor == -2 -> bfsSf
        else -> bfs[cursor + 1]
    }

    /** 当前位置若是取件点，则它在货架/柜列里的区内走位（瓷砖） */
    fun cursorStub(): Double = if (cursor >= 0) spots[cursor].stubTiles else 0.0

    fun cursorLabel(): String = when (cursor) {
        -1 -> startLabel
        -2 -> "顺丰出库（顺丰专用闸机）"
        else -> spots[cursor].code.toString()
    }

    for (node in seq) {
        when {
            node == n -> {
                val d = if (cursor == -1) minToCells(bfs[0], listOf(sfPoint())).toDouble()
                else bfsDistTo(bfs[cursor + 1], sfPoint().row, sfPoint().col).toDouble() + stubCells[cursor]
                legList += RouteLeg(
                    from = cursorLabel(),
                    to = "顺丰出库（顺丰专用闸机）",
                    kind = LegKind.SF_CHECKOUT,
                    cells = cellsOf(sourceBfs(), sfPoint().row, sfPoint().col),
                    tiles = d * CELL,
                    stubFromTiles = cursorStub(),
                    stubToTiles = 0.0,
                )
                stops += RouteStop.SfCheckout
                cursor = -2
            }
            else -> {
                val d = when {
                    cursor == -1 -> fromEntrance[node]
                    cursor == -2 -> bfsDistTo(bfs[node + 1], sfPoint().row, sfPoint().col).toDouble() + stubCells[node]
                    else -> pair(cursor, node)
                }
                legList += RouteLeg(
                    from = cursorLabel(),
                    to = spots[node].code.toString(),
                    kind = if (cursor == -1) LegKind.ENTRANCE else LegKind.PICK,
                    cells = cellsOf(sourceBfs(), spots[node].row, spots[node].col),
                    tiles = d * CELL,
                    stubFromTiles = cursorStub(),
                    stubToTiles = spots[node].stubTiles,
                )
                stops += RouteStop.Pickup(spots[node].code, spots[node])
                orderedCodes += spots[node].code
                cursor = node
            }
        }
    }
    // 终点：出站（出库 ≠ 出站）
    val exitCell: GridCell?
    val exitLegTiles: Double
    val exitCells: List<GridCell>
    val exitStubFrom = cursorStub()
    if (exitKind == RouteExit.NORMAL_GATE) {
        if (cursor == -2) {
            val pair = bestPair(listOfNotNull(sfCell), normalGates)
            exitCell = pair.second
            exitLegTiles = pair.first * CELL
            exitCells = pair.second?.let { cellsOf(bfsSf, it.row, it.col) } ?: emptyList()
        } else {
            val bfsCur = bfs[cursor + 1]
            val best = normalGates.minByOrNull { bfsDistTo(bfsCur, it.row, it.col) }
            exitCell = best
            exitLegTiles = if (best == null) 0.0
            else bfsDistTo(bfsCur, best.row, best.col).toDouble() * CELL + exitStubFrom
            exitCells = best?.let { cellsOf(bfsCur, it.row, it.col) } ?: emptyList()
        }
    } else {
        if (cursor != -2) {
            // 理论上不会发生（只有顺丰件 ⇒ 出库点必在最后）
            val bfsCur = bfs[cursor + 1]
            val best = sfExitGates.minByOrNull { bfsDistTo(bfsCur, it.row, it.col) }
            exitCell = best
            exitLegTiles = if (best == null) 0.0
            else bfsDistTo(bfsCur, best.row, best.col).toDouble() * CELL + exitStubFrom
            exitCells = best?.let { cellsOf(bfsCur, it.row, it.col) } ?: emptyList()
        } else {
            // bestPair(from, to) 的 second 是 to 里的格 ⇒ 出站点要从 sfExitGates 里挑
            val pair = bestPair(listOfNotNull(sfCell), sfExitGates)
            exitCell = pair.second
            exitLegTiles = pair.first * CELL
            exitCells = pair.second?.let { cellsOf(bfsSf, it.row, it.col) } ?: emptyList()
        }
    }
    legList += RouteLeg(
        from = cursorLabel(),
        to = exitKind.label,
        kind = LegKind.EXIT,
        cells = exitCells,
        tiles = exitLegTiles,
        stubFromTiles = exitStubFrom,
        stubToTiles = 0.0,
    )
    stops += RouteStop.Exit(exitKind)

    val sfAfter = stops.indexOfFirst { it is RouteStop.SfCheckout }
    val sfAfterPicks = if (sfAfter < 0) -1 else stops.take(sfAfter).count { it is RouteStop.Pickup }

    return PickupRoute(
        orderedCodes = orderedCodes,
        stops = stops,
        legs = legList,
        totalTiles = legList.sumOf { it.tiles },
        sfCheckoutAfter = sfAfterPicks,
        sfCheckoutCell = SiteIndex.sfCheckoutCell,
        exitCell = exitCell,
        exit = exitKind,
        lockerCodes = lockers,
        unresolved = unresolved.toList(),
        exact = n <= MAX_EXACT_ITEMS,
    )
}

/** 两组格子之间取最近的一对，返回 (距离/单元格, 终点格)。 */
private fun bestPair(from: List<GridCell>, to: List<GridCell>): Pair<Int, GridCell?> {
    var best = INF
    var bestTo: GridCell? = null
    for (a in from) {
        val bfs = SiteModel.bfs(a.row, a.col) ?: continue
        for (b in to) {
            val d = bfsDistTo(bfs, b.row, b.col)
            if (d < best) {
                best = d; bestTo = b
            }
        }
    }
    return best to bestTo
}
