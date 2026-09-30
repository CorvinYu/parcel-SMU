package com.xxxx.parcel.util

/**
 * 「怎么走到下一个取货点」——把 [RouteLeg] 的**通道格序列**翻译成人话。
 *
 * ## 为什么能做到
 *
 * 引擎的每一段都是 BFS 回溯出来的单元格序列（每步相邻），所以：
 * 1. 把序列压成「同方向的一段段」⇒ 天然得到「向东 5 格 → 向北 6 格」；
 * 2. 每一段落在哪条**横向走廊**（`SiteModel.analyze()` 认出来的 9 条带）或哪条**纵向干线**
 *    （西侧靠闸机 / 主通道 / 东侧靠 Y 区）⇒ 可以说出「沿第 N 条横走廊」；
 * 3. 走廊带用相邻货架字母做地标（`B/A 排之间`），不需要用户记行号。
 *
 * ## 铁律
 *
 * - **一切坐标来自数据**：走廊/干线的行号列号不手写，全部由 [SiteModel.analyze] 从用户 Excel 的
 *   填充色推出（改 Excel → 重跑 `gen-site-data-kt.py` → 提示自动跟着变）。
 * - **不许承诺没有的精度**：普通排每货架的实际格子数未实测（同货架不同格暂视为同一点）、
 *   Y 区在精确版里是一整块 —— 文案必须如实标注，而不是编一个「第 23 格在货架东侧」。
 */
internal object VenueGuide {

    /** 站点绝对方位。**北 = 往里**（行号减小）、**南 = 往门口**（行号增大）、东 = 列增大。 */
    enum class Dir(val label: String) {
        NORTH("向北（往里）"),
        SOUTH("向南（往门口）"),
        EAST("向东"),
        WEST("向西"),
    }

    /** 一条横向走廊带（把 `analyze()` 里被主通道 1 格断开的两块合并成一条）。 */
    data class Band(
        /** 从入口往里数的序号，1 起 */
        val index: Int,
        val r0: Int,
        val r1: Int,
        val c0: Int,
        val c1: Int,
        /** 相邻货架字母地标，如 `B/A 排之间`；推不出来时为空串 */
        val landmark: String,
    ) {
        /** 「第 3 条横走廊（F/E 排之间）」 */
        val name: String get() = if (landmark.isEmpty()) "第${index}条横走廊" else "第${index}条横走廊（$landmark）"
        /** 简洁名：「第 3 条横走廊」 */
        val shortName: String get() = "第${index}条横走廊"
    }

    /** 一条纵向干线。 */
    data class Trunk(val c0: Int, val c1: Int, val r0: Int, val r1: Int, val name: String)

    // ---------------------------------------------------------------- 结构

    /** 9 条横向走廊带（从入口往里编号）。 */
    val bands: List<Band> by lazy {
        val raw = SiteModel.analyze().corridors
            .filter { it.c1 - it.c0 >= 30 }
            .sortedByDescending { it.r0 }
        // 🔴 必须按行区间合并：最南那条被主通道 1 格断开 ⇒ analyze 给出 2 个矩形，不合并「第 N 条」会整体错位
        val merged = ArrayList<IntArray>()   // [r0, r1, c0, c1]
        for (s in raw) {
            val hit = merged.firstOrNull { it[0] == s.r0 && it[1] == s.r1 }
            if (hit == null) merged += intArrayOf(s.r0, s.r1, s.c0, s.c1)
            else {
                hit[2] = minOf(hit[2], s.c0)
                hit[3] = maxOf(hit[3], s.c1)
            }
        }
        merged.mapIndexed { i, m ->
            Band(index = i + 1, r0 = m[0], r1 = m[1], c0 = m[2], c1 = m[3], landmark = landmarkOf(m[0], m[1]))
        }
    }

    /** 3 条纵向干线：西侧（靠闸机）/ 主通道 / 东侧（靠 Y 区）。名字按相对主通道中心的位置判定。 */
    val trunks: List<Trunk> by lazy {
        val long = SiteModel.analyze().trunks.filter { it.r1 - it.r0 >= 30 }.sortedBy { it.c0 }
        long.mapIndexed { i, t ->
            val center = (t.c0 + t.c1 + 1) / 2.0
            val name = when {
                long.size == 3 && i == 1 -> "主通道"
                kotlin.math.abs(center - SiteData.SPINE_COL) <= 3.0 -> "主通道"
                center < SiteData.SPINE_COL -> "西侧通道（靠闸机）"
                else -> "东侧通道（靠 Y 区）"
            }
            Trunk(t.c0, t.c1, t.r0, t.r1, name)
        }
    }

    fun bandAt(row: Int): Band? = bands.firstOrNull { row in it.r0..it.r1 }

    fun trunkAt(col: Int): Trunk? = trunks.firstOrNull { col in it.c0..it.c1 }

    /** 走廊带的地标：两侧相邻的货架字母（+ 闸机提示）。 */
    private fun landmarkOf(r0: Int, r1: Int): String {
        val north = LinkedHashSet<Char>()
        val south = LinkedHashSet<Char>()
        var gateSide = false
        var i = 0
        while (i < SiteData.rectLabels.size) {
            val label = SiteData.rectLabels[i].trim()
            val b = i * 4
            if (label.isNotEmpty()) {
                if (label.contains("闸机") || label.contains("出口")) {
                    if (SiteData.rectBounds[b + 3] == r0 - 1 || SiteData.rectBounds[b + 2] == r1 + 1) gateSide = true
                } else if (Regex("^[A-Z]\\d{1,2}$").matches(label)) {
                    val letter = label[0]
                    if (SiteData.rectBounds[b + 3] == r0 - 1) north += letter   // 行号更小 = 更往里 = 北侧
                    if (SiteData.rectBounds[b + 2] == r1 + 1) south += letter   // 行号更大 = 更靠门口 = 南侧
                }
            }
            i++
        }
        val core = when {
            north.isNotEmpty() && south.isNotEmpty() -> "${north.joinToString("/")}/${south.joinToString("/")} 排之间"
            north.isNotEmpty() -> "${north.joinToString("/")} 排北侧"
            south.isNotEmpty() -> "${south.joinToString("/")} 排南侧"
            else -> ""
        }
        return when {
            core.isEmpty() && gateSide -> "闸机侧"
            gateSide -> "$core，闸机侧"
            else -> core
        }
    }

    // ---------------------------------------------------------------- 文案

    enum class HintKind {
        /** 从上一件货架/柜列走回通道 */
        STUB_OUT,

        /** 沿通道走 */
        MOVE,

        /** 走进货架/柜列 */
        STUB_IN,

        /** 到达 */
        ARRIVE,

        /** 精度/前提说明（如实标注，不编业务） */
        NOTE,
    }

    /** 一条提示。[text] 是详细版、[brief] 是简洁版（界面按「详情程度」开关二选一）。 */
    data class WalkHint(
        val kind: HintKind,
        val text: String,
        val brief: String,
        val tiles: Double,
        val endCell: GridCell? = null,
        val band: Band? = null,
        val trunk: Trunk? = null,
        /** 这一段的走向（图示窗格画箭头用；非位移提示为 null） */
        val dir: Dir? = null,
    )

    /** 把一段路线翻译成逐条提示。目标无法定位/序列为空时返回空列表（界面如实说明）。 */
    fun describe(leg: RouteLeg, target: PickupSpot? = null): List<WalkHint> {
        val out = ArrayList<WalkHint>()
        if (leg.cells.isEmpty()) return out

        if (leg.stubFromTiles > 0.01) {
            val t = "从${leg.from}走回通道 ${fmt(leg.stubFromTiles)} 格"
            out += WalkHint(HintKind.STUB_OUT, t, t, leg.stubFromTiles, leg.cells.first())
        }

        var i = 0
        while (i < leg.cells.size - 1) {
            val dr = leg.cells[i + 1].row - leg.cells[i].row
            val dc = leg.cells[i + 1].col - leg.cells[i].col
            var j = i
            while (j + 2 < leg.cells.size) {
                val nr = leg.cells[j + 2].row - leg.cells[j + 1].row
                val nc = leg.cells[j + 2].col - leg.cells[j + 1].col
                if (nr != dr || nc != dc) break
                j++
            }
            val steps = j - i + 1
            val endCell = leg.cells[j + 1]
            val dir = dirOf(dr, dc)
            val band = bandAt(endCell.row)
            val trunk = trunkAt(endCell.col)
            val onGate = SiteModel.kindAt(endCell.row, endCell.col) == SiteModel.GATE
            val tiles = steps * SiteModel.CELL_TILES

            val full: String
            val brief: String
            when {
                onGate -> {
                    full = "穿过闸机区${dir.label} $steps 格"
                    brief = "${dir.label} $steps 格"
                }
                dr == 0 && band != null -> {
                    full = "沿${band.name}${dir.label} $steps 格"
                    brief = "${dir.label} $steps 格"
                }
                dc == 0 && trunk != null -> {
                    full = "沿${trunk.name}${dir.label} $steps 格"
                    brief = "${dir.label} $steps 格"
                }
                dc == 0 && band != null -> {
                    // 纵向位移但人在走廊带里 ⇒ 是在走廊里换边/绕行，不是「沿通道」
                    full = "在${band.name}内${dir.label} $steps 格"
                    brief = "${dir.label} $steps 格"
                }
                band != null -> {
                    full = "沿${band.name}${dir.label} $steps 格"
                    brief = "${dir.label} $steps 格"
                }
                else -> {
                    full = "沿通道${dir.label} $steps 格"
                    brief = "${dir.label} $steps 格"
                }
            }
            out += WalkHint(HintKind.MOVE, full, brief, tiles, endCell, band, trunk, dir)
            i = j + 1
        }

        if (leg.stubToTiles > 0.01) {
            val where = when {
                target != null && target.code.zone == PickupZone.J_CABINET ->
                    "走进柜列 ${fmt(leg.stubToTiles)} 格取 ${leg.to}"
                else -> "走进货架 ${fmt(leg.stubToTiles)} 格取 ${leg.to}"
            }
            out += WalkHint(HintKind.STUB_IN, where, where, leg.stubToTiles, leg.cells.last(), dir = stubDir(leg, target))
        }

        out += WalkHint(HintKind.ARRIVE, "到达 ${leg.to}", "到达 ${leg.to}", 0.0, leg.cells.last())
        noteFor(target)?.let { out += WalkHint(HintKind.NOTE, it, it, 0.0, leg.cells.last()) }
        return out
    }

    /** 一行短提示（首页列表用）：最多 3 个转向，多了截断。 */
    fun summarize(leg: RouteLeg, target: PickupSpot? = null): String {
        if (leg.cells.isEmpty()) return ""
        val moves = describe(leg, target).filter { it.kind == HintKind.MOVE }
        if (moves.isEmpty()) return ""
        val head = moves.take(3).joinToString(" → ") { it.brief }
        return if (moves.size > 3) "$head →…" else head
    }

    /** 精度说明：只说数据支持的，不编。 */
    private fun noteFor(target: PickupSpot?): String? {
        target ?: return null
        return when {
            target.approximate ->
                "Y 区在精确版 Excel 里是一整块 ⇒ 只能带到东侧通道口，进区后按 y1~y8 标牌找"
            target.code.zone == PickupZone.MAIN && target.code.cellNumber != null ->
                "该货架每排实际格子数未实测 ⇒ 到货架后按格上编号找第 ${target.code.cellNumber} 格"
            target.code.zone == PickupZone.SF && target.code.cellNumber != null ->
                "顺丰 s${target.code.shelfNumber} 第 ${target.code.cellNumber} 格（左端为 1，按格位横向铺开）"
            else -> null
        }
    }

    private fun dirOf(dr: Int, dc: Int): Dir = when {
        dr < 0 -> Dir.NORTH
        dr > 0 -> Dir.SOUTH
        dc > 0 -> Dir.EAST
        else -> Dir.WEST
    }

    /** 从通道格走进货架/柜列的方向（按格位精确点算；推不出来返回 null）。 */
    private fun stubDir(leg: RouteLeg, target: PickupSpot?): Dir? {
        target ?: return null
        val from = leg.cells.lastOrNull() ?: return null
        val dr = SiteModel.rowOf(target.depth) - from.row
        val dc = SiteModel.colOf(target.lat) - from.col
        if (kotlin.math.abs(dr) < 0.5 && kotlin.math.abs(dc) < 0.5) return null
        return if (kotlin.math.abs(dr) >= kotlin.math.abs(dc)) {
            if (dr < 0) Dir.NORTH else Dir.SOUTH
        } else {
            if (dc > 0) Dir.EAST else Dir.WEST
        }
    }

    private fun fmt(value: Double): String {
        val rounded = kotlin.math.round(value * 10.0) / 10.0
        return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
    }
}
