package com.xxxx.parcel.util

import kotlin.math.abs

/**
 * 取件最优路径引擎（纯 Kotlin，无 Android 依赖 —— 便于在 JVM 上跑单元测试）。
 *
 * ## 场地拓扑（依据用户 2026-09-29 现场踩点图 `docs/海大快递站平面布局与货位清单.xlsx`）
 *
 * ```
 *            ↑ 北（最里侧：J 柜列 / S 顺丰区 / Y 大件区）
 *            │
 *   通道11 ──┼──────────────────────  ← 挂 J 柜列、S 顺丰区
 *   R 排    ─┤        主纵向通道      ← 货架 1~4 ┃ 5~12
 *   通道13 ──┼──────────────────────  ← 服务 Q 排（南侧）与 R 排（北侧）
 *   Q 排    ─┤
 *   通道16 ──┼──────────────────────  ← 服务 O 排与 P 排
 *    ...
 *   通道34 ──┼──────────────────────  ← 服务 A 排与 B 排（离入口最近）
 *   A 排    ─┤
 *            ↓ 南（入口大门 J39:M40）
 * ```
 *
 * 已确认的关键结构（踩点图 + 用户说明）：
 * 1. **16 排**，由入口向里依次 `A B C D E F G H K L M N O P Q R`（**无 I、无 J**；J 是独立柜列区）。
 * 2. **每两排背靠背、共用一条横向通道**：A/B 共用通道 0（最靠近入口），C/D 用通道 1，…，Q/R 用通道 7，
 *    J/S 区挂在最里侧的通道 8。原图 9 条横向通道 ↔ 16 排，正好一一对应。
 * 3. **主纵向通道在货架 4 与 5 之间**（原图 `N11:N33`），入口在通道南端 ⇒ 先沿它走到目标通道，再横向到货架。
 * 4. 每排 12 个货架：通道**西侧 1~4**、**东侧 5~12**。
 * 5. **同一通道的南北两侧可以横穿**（背靠背的两排之间不必绕回主通道）。
 * 6. 货架内格子编号是「每行从左到右」的阅读序 —— 但**每货架格数尚未实测**，故同一货架内先视作同一点。
 *
 * ## 特殊区（本轮起正式纳入规划）
 *
 * | 区 | 结构 | 格子方向 |
 * |---|---|---|
 * | J | 北端靠西墙的 **6 条纵向柜列**：`j1 │ 通道 │ j2 j3（背靠背）│ 通道 │ j4 j5（背靠背）│ 通道 │ j6` | 沿列**向里**递增 |
 * | S | 顺丰 3 个货架 `s1`(10) `s2`(8) `s3`(8) | 沿横向（西→东）递增 |
 * | Y | 大件 `y1`~`y7` + `y8`（8 行 × 3 子位，`y8-<行>-<子位>`） | `y2/y4/y5/y7` **反向**（右端为 1） |
 *
 * ## 为什么用「精确 DP」而不是贪心
 *
 * 走行图是「纵向主通道 + 每条横向通道」构成的树，加上「同通道可横穿」这一条近路。
 * 距离满足对称与三角不等式（有单元测试守住），因此「每件恰好访问一次」的最短路线可用
 * Held–Karp 子集 DP 求**精确最优**；DP 结果与暴力枚举逐例比对（见 `PickupRouteTest`）。
 *
 * ## 仍是近似、已如实标注的地方（只影响绝对格数，不影响相对顺序）
 *
 * - 通道间距默认 3 格、入口到主通道 4 格、横穿代价 1 格 —— 全部可在界面调整。
 * - J 六条柜列的横向距离按**列序** 1~6（j6 最靠主通道）；柜内格子沿列每格 1 格。
 * - S / Y 的格子横向按格号 1..N（踩点图是示意图，非等距）。
 * - Y 区 `y8-3`~`y8-7` 在原图写作「……」，其深度按 `y8-2` 与 `y8-8` 之间均分推断。
 * - a 区排列（原图 a3 重复出现）尚未确认，故 a 排只定位到「第几个货架」，不做排内细分。
 */

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

/** 相对主纵向通道的一侧。 */
enum class SpineSide(val label: String) {
    WEST("通道西侧"),
    EAST("通道东侧"),
}

/** 相对横向通道的一侧（背靠背的两排分属两侧）。 */
enum class AisleSide(val label: String) {
    SOUTH("通道南侧"),
    NORTH("通道北侧"),
}

/** 一个可寻址点的场地坐标（相对主纵向通道与它所属的横向通道）。 */
data class SitePosition(
    val zone: PickupZone,
    /** 沿主纵向通道、从入口方向算起的格数 */
    val depthTiles: Int,
    /** 从主纵向通道中心线横向走到该点的格数（≥1） */
    val lateralTiles: Int,
    val spineSide: SpineSide,
    val aisleSide: AisleSide,
    /** 服务它的横向通道序号（0 = 最靠近入口那条） */
    val aisle: Int,
    /** 给人看的描述 */
    val label: String,
    val code: CompartmentCode,
)

/**
 * 特殊区里的一个「货架级」单元：J 柜列、顺丰货架、大件货架。
 *
 * 坐标含义见 [SitePosition]；[reversed] 用于踩点图里反向编号的 Y 货架，
 * [cellsAlongDepth] 用于 J 柜列（格子沿纵向向里递增）。
 */
data class SpecialShelf(
    val zone: PickupZone,
    val shelfNumber: Int,
    val maxCell: Int,
    val depthTiles: Int,
    val lateralBase: Int,
    val spineSide: SpineSide,
    val aisleSide: AisleSide,
    val aisle: Int,
    val reversed: Boolean = false,
    val cellsAlongDepth: Boolean = false,
    /** 第三段是否为「横向子位」（仅 Y8 用） */
    val thirdSegmentIsSubSlot: Boolean = false,
    val subSlots: Int = 1,
    /** 非空时：第二段是「行号」，用它索引该行所在的深度（仅 Y8 用） */
    val perRowDepths: List<Int> = emptyList(),
    val label: String,
)

/**
 * 场地布局参数。
 *
 * 默认值全部来自用户 2026-09-29 现场踩点图（见 `docs/` 下的平面布局表），
 * 但**仍是可编辑配置**：与现场不符时改参数即可，不要改引擎逻辑。
 */
data class SiteLayout(
    /** 由入口向深处的普通排字母（不含特殊区）。默认 16 排，无 I、无 J。 */
    val rowLetters: List<Char> = DEFAULT_ROW_LETTERS,
    val shelvesPerRow: Int = 12,
    /** 主通道西侧的货架号上界（默认 4 ⇒ 西 1~4、东 5~12） */
    val leftBlockEnd: Int = 4,
    /** 相邻两条横向通道之间的纵向格数 */
    val aisleSpacingTiles: Int = 3,
    /** 入口到主通道口的横向格数（用户口述「向右 4 块瓷砖」） */
    val doorToSpineTiles: Int = 4,
    /** 同一条通道上、南北异侧之间横穿的代价 */
    val crossAisleTiles: Int = 1,
    val specialShelves: List<SpecialShelf> = defaultSpecialShelves(),
) {
    /** 最里侧那条通道的序号（J 柜列与 S 顺丰区挂在它上面） */
    val innermostAisle: Int get() = rowLetters.size / 2

    /** 每条排所属的通道序号：**每两排背靠背共用一条通道**。 */
    fun aisleOf(rowIndex: Int): Int = rowIndex / 2

    /** 同一条通道的哪一侧：偶数下标在前（南侧），奇数在后（北侧）。 */
    fun aisleSideOf(rowIndex: Int): AisleSide =
        if (rowIndex % 2 == 0) AisleSide.SOUTH else AisleSide.NORTH

    companion object {
        /** 由入口向里的 16 排（踩点图实测：`R Q P O N M L K H G F E D C B A` 反转，去掉 I/J）。 */
        val DEFAULT_ROW_LETTERS: List<Char> =
            listOf('A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R')

        fun default(): SiteLayout = SiteLayout()

        /** 特殊区默认坐标：全部据 2026-09-29 踩点图换算（通道间距按 3 格）。 */
        fun defaultSpecialShelves(): List<SpecialShelf> {
            val result = mutableListOf<SpecialShelf>()
            // ---- J 柜列：北端靠西墙的 6 条纵向柜列，j6 最靠主通道 ----
            for (i in 1..6) {
                result += SpecialShelf(
                    zone = PickupZone.J_CABINET,
                    shelfNumber = i,
                    maxCell = 21,
                    depthTiles = 25,          // 原图标签在第 10 行 ⇒ 通道 11 以北 1 格
                    lateralBase = 7 - i,      // j1=6 … j6=1
                    spineSide = SpineSide.WEST,
                    aisleSide = AisleSide.NORTH,
                    aisle = 8,
                    cellsAlongDepth = true,
                    label = "J 区 j$i 柜列",
                )
            }
            // ---- S 顺丰区：3 个货架，位于主通道以东 ----
            listOf(
                Triple(1, 10, 28),   // s1：货位 1~10，原图第 7 行
                Triple(2, 8, 26),    // s2：1~8，第 9 行
                Triple(3, 8, 25),    // s3：1~8，第 10 行（紧邻通道 11）
            ).forEach { (num, maxCell, depth) ->
                result += SpecialShelf(
                    zone = PickupZone.SF,
                    shelfNumber = num,
                    maxCell = maxCell,
                    depthTiles = depth,
                    lateralBase = 1,
                    spineSide = SpineSide.EAST,
                    aisleSide = AisleSide.NORTH,
                    aisle = 8,
                    label = "S 区（顺丰）s$num",
                )
            }
            // ---- Y 大件区：y1~y7（y2/y4/y5/y7 反向编号） ----
            listOf(
                // 货架号, 格数, 深度, 是否反向, 通道序号
                listOf(1, 9, 30, 0, 8),
                listOf(2, 4, 28, 1, 8),
                listOf(3, 4, 27, 0, 8),
                listOf(4, 4, 25, 1, 8),
                listOf(5, 7, 21, 1, 7),
                listOf(6, 8, 21, 0, 7),
                listOf(7, 8, 18, 1, 6),
            ).forEach { spec ->
                val num = spec[0]
                result += SpecialShelf(
                    zone = PickupZone.BULK,
                    shelfNumber = num,
                    maxCell = spec[1],
                    depthTiles = spec[2],
                    lateralBase = 9,          // 位于纵向通道 V 之东（货架 12 之外）
                    spineSide = SpineSide.EAST,
                    aisleSide = AisleSide.NORTH,
                    aisle = spec[4],
                    reversed = spec[3] == 1,
                    label = "Y 区（大件）y$num",
                )
            }
            // ---- Y 大件区 y8：8 行 × 3 子位，每行独占一行 ----
            result += SpecialShelf(
                zone = PickupZone.BULK,
                shelfNumber = 8,
                maxCell = 8,
                depthTiles = 15,
                lateralBase = 9,
                spineSide = SpineSide.EAST,
                aisleSide = AisleSide.NORTH,
                aisle = 5,
                thirdSegmentIsSubSlot = true,
                subSlots = 3,
                // y8-1、y8-2、y8-8 在原图有标注；y8-3~y8-7 原图写「……」，按两端均分推断
                perRowDepths = listOf(15, 12, 11, 10, 9, 8, 7, 6),
                label = "Y 区（大件）y8 柜组",
            )
            return result
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

/**
 * 从「取件码」文本里识别**人工货架 / 顺丰 / 大件**的货格号。
 *
 * 为什么需要它：上游 `SmsParser` 的 `compartmentNumber` 只从「格口」「N号柜」这类**快递柜**写法里提取
 * （见 `SmsParser.compartmentPattern` 等三条正则），人工货架短信（`请用D8-6到人工货架取包裹`）
 * 的货格号一直只被当成取件码存进 `code`——于是路线功能永远拿不到输入、页面永远是空的。
 * 这里做兜底：**取件码本身就是货格号时，把它也当作货格号**。
 *
 * 只认「排字母 + 货架号(-格号)」，因此不会误伤快递柜与其他编号：
 * - 纯数字（`54018314`）⇒ 快递柜
 * - 无排字母（`23-32`、`8-3-2018`）⇒ 不匹配
 * - 字母开头但不是「字母+数字」（`SF1234567890`、`JD12345678`）⇒ 不匹配
 *
 * @return 归一化成大写的货格号；识别不出返回 null
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

private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

/**
 * 一个件的**有效货格号**：优先用短信解析出的货格号；为空时退回「取件码本身就是货格号」。
 *
 * 这样路线功能就不必依赖解析器的 `compartmentNumber` 是否正确填充——哪怕上游规则只认快递柜写法，
 * 只要取件码长得像货格号（`D8-6`、`S3-2-2628`），排序与路线照样能算。
 */
fun effectiveCompartmentNumber(compartmentNumber: String, code: String): String =
    compartmentNumber.trim().ifBlank { compartmentFromPickupCode(code) ?: "" }

/** 货架号 → 距主纵向通道的横向格数；越靠近通道越小。 */
fun lateralTilesFor(shelfNumber: Int, layout: SiteLayout): Int? {
    if (shelfNumber < 1 || shelfNumber > layout.shelvesPerRow) return null
    return if (shelfNumber <= layout.leftBlockEnd) {
        // 西侧：通道在西段的东端 ⇒ 4 号最近（1 格），1 号最远
        layout.leftBlockEnd - shelfNumber + 1
    } else {
        // 东侧：通道在东段的西端 ⇒ 5 号最近（1 格），12 号最远
        shelfNumber - layout.leftBlockEnd
    }
}

/** 把货格号定位到场地坐标；无法定位（未知字母、货架号越界）返回 null。 */
fun locate(code: CompartmentCode, layout: SiteLayout): SitePosition? =
    locateMain(code, layout) ?: locateSpecial(code, layout)

private fun locateMain(code: CompartmentCode, layout: SiteLayout): SitePosition? {
    val rowIndex = layout.rowLetters.indexOf(code.rowLetter)
    if (rowIndex < 0) return null
    val lateral = lateralTilesFor(code.shelfNumber, layout) ?: return null
    val aisle = layout.aisleOf(rowIndex)
    val side = if (code.shelfNumber <= layout.leftBlockEnd) SpineSide.WEST else SpineSide.EAST
    val cell = code.cellNumber
    return SitePosition(
        zone = PickupZone.MAIN,
        depthTiles = aisle * layout.aisleSpacingTiles,
        lateralTiles = lateral,
        spineSide = side,
        aisleSide = layout.aisleSideOf(rowIndex),
        aisle = aisle,
        label = "${code.rowLetter} 排 ${code.shelfNumber} 号货架" +
            (cell?.let { " 第 $it 格" } ?: "") +
            "（${side.label}，距通道 $lateral 格，${layout.aisleSideOf(rowIndex).label}）",
        code = code,
    )
}

private fun locateSpecial(code: CompartmentCode, layout: SiteLayout): SitePosition? {
    if (code.zone == PickupZone.MAIN || code.zone == PickupZone.UNKNOWN) return null
    val spec = layout.specialShelves.firstOrNull {
        it.zone == code.zone && it.shelfNumber == code.shelfNumber
    } ?: return null

    val cell = code.cellNumber ?: 1
    var depth = spec.depthTiles
    var lateral = spec.lateralBase
    var detail: String

    when {
        // y8：第二段是「行号」（决定深度），第三段是横向子位
        spec.thirdSegmentIsSubSlot && spec.perRowDepths.isNotEmpty() -> {
            val rowIndex = (cell - 1).coerceIn(0, spec.perRowDepths.size - 1)
            depth = spec.perRowDepths[rowIndex]
            val sub = (code.subNumber ?: 1).coerceIn(1, spec.subSlots)
            lateral = spec.lateralBase + (sub - 1)
            detail = "第 ${rowIndex + 1} 行 · 第 $sub 子位（横向展开）"
        }
        // J 柜列：格子沿列向里递增 ⇒ 影响深度
        spec.cellsAlongDepth -> {
            depth = spec.depthTiles + (cell - 1)
            detail = "第 $cell 格（沿列向里递增）"
        }
        // Y 反向编号的货架：右端为 1
        spec.reversed -> {
            lateral = (spec.lateralBase + (spec.maxCell - cell)).coerceAtLeast(1)
            detail = "第 $cell 格（右端为 1，向左递增）"
        }
        else -> {
            lateral = spec.lateralBase + (cell - 1)
            detail = "第 $cell 格（左端为 1，向右递增）"
        }
    }

    return SitePosition(
        zone = spec.zone,
        depthTiles = depth,
        lateralTiles = lateral,
        spineSide = spec.spineSide,
        aisleSide = spec.aisleSide,
        aisle = spec.aisle,
        label = "${spec.label} · $detail",
        code = code,
    )
}

/** 入口 → 该点 的步数。 */
fun entranceToTiles(target: SitePosition, layout: SiteLayout): Int =
    layout.doorToSpineTiles + target.depthTiles + target.lateralTiles

/** 该点 → 出口 的步数（出口与入口同一处，故与 [entranceToTiles] 相同）。 */
fun exitFromTiles(target: SitePosition, layout: SiteLayout): Int =
    entranceToTiles(target, layout)

/**
 * 两点之间的步数（走行图上的最短路径）。
 *
 * - **同一条横向通道、同一侧**：直接沿通道走 `|Δ横向|`（外加 J 柜列那种沿纵向的 `|Δ深度|`）。
 * - **同一条横向通道、南北异侧**：可横穿，代价 `crossAisleTiles`。
 * - **不同通道**：必须回主纵向通道 ⇒ `横向 + |Δ深度| + 横向`。
 */
fun walkTiles(a: SitePosition, b: SitePosition, layout: SiteLayout): Int {
    val depthGap = abs(a.depthTiles - b.depthTiles)
    if (a.aisle == b.aisle) {
        val lateral = if (a.spineSide == b.spineSide) {
            abs(a.lateralTiles - b.lateralTiles)
        } else {
            a.lateralTiles + b.lateralTiles
        }
        val cross = if (a.aisleSide == b.aisleSide) 0 else layout.crossAisleTiles
        return lateral + depthGap + cross
    }
    return a.lateralTiles + depthGap + b.lateralTiles
}

/** 规划结果。 */
data class PickupRoute(
    /** 建议的取件顺序（已成功定位的件） */
    val orderedCodes: List<CompartmentCode>,
    /** 总步数（格） */
    val totalTiles: Int,
    /** 每一段的步数：第 0 段为「入口 → 第 1 件」，之后逐件；折返时最后一段为「末件 → 出口」 */
    val legTiles: List<Int>,
    /** 纯数字取件码 = 快递柜，不在人工货架路径上 */
    val lockerCodes: List<String>,
    /** 完全无法定位的原文 */
    val unresolved: List<String>,
    /** 是否为精确最优（false 表示件数过多，退化为启发式） */
    val exact: Boolean,
) {
    val resolvedCount: Int get() = orderedCodes.size

    /** 各分区的件数统计。 */
    fun zoneCounts(): Map<PickupZone, Int> =
        orderedCodes.groupingBy { it.zone }.eachCount()
}

/** 该取件码是否只是数字（快递柜）。 */
fun isLockerCode(raw: String): Boolean {
    val text = raw.trim()
    return text.isNotEmpty() && text.all { it.isDigit() }
}

/**
 * 取件地点类型。
 *
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
 * 三大类判定。
 *
 * 判据（依用户真实样例）：
 * 1. 短信**正文**里出现「海事大学」⇒ 校内；否则 ⇒ 校外
 * 2. 校内 且 取件码为纯数字 ⇒ 快递柜
 * 3. 校内 且 取件码含字母 ⇒ 快递站（含顺丰 S、大件 Y、J 柜列）
 *
 * ⚠️ 必须用短信正文而不是解析后的地址：`D8-6` 那条的解析地址是「请用D8-6到人工货架取包裹」，
 * 里面**没有**「海事大学」，用地址判断会把它误判成校外。
 */
fun classifyPickupCategory(code: String, smsBody: String): PickupCategory {
    if (!smsBody.contains("海事大学")) return PickupCategory.OFF_CAMPUS
    return if (isLockerCode(code)) PickupCategory.LOCKER else PickupCategory.STATION
}

/** 超过这个件数就不用 O(2^n·n²) 的精确 DP，退化为最近邻 + 2-opt。 */
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
    val lockers = mutableListOf<String>()
    val located = mutableListOf<SitePosition>()
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
        val pos = locate(code, layout)
        if (pos == null) {
            unresolved += raw
            continue
        }
        located += pos
    }

    if (located.isEmpty()) {
        return PickupRoute(
            orderedCodes = emptyList(),
            totalTiles = 0,
            legTiles = emptyList(),
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
        lockerCodes = lockers,
        unresolved = unresolved,
        exact = useExact,
    )
}

// ===== 精确求解（Held–Karp 子集 DP）=====

private fun solveExact(
    targets: List<SitePosition>,
    layout: SiteLayout,
    returnToEntrance: Boolean,
): List<SitePosition> {
    val n = targets.size
    val full = 1 shl n

    val fromEntrance = IntArray(n) { entranceToTiles(targets[it], layout) }
    val toExit = IntArray(n) { exitFromTiles(targets[it], layout) }
    val between = Array(n) { i -> IntArray(n) { j -> walkTiles(targets[i], targets[j], layout) } }

    val inf = Int.MAX_VALUE / 4
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
    targets: List<SitePosition>,
    layout: SiteLayout,
    returnToEntrance: Boolean,
): List<SitePosition> {
    val n = targets.size
    val remaining = targets.indices.toMutableList()
    val order = ArrayList<Int>(n)

    var current: SitePosition? = null
    while (remaining.isNotEmpty()) {
        val pick = remaining.minBy { idx ->
            val t = targets[idx]
            if (current == null) entranceToTiles(t, layout) else walkTiles(current, t, layout)
        }
        order += pick
        remaining.remove(pick)
        current = targets[pick]
    }

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
