package com.xxxx.parcel.ui.components

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xxxx.parcel.model.SmsData
import com.xxxx.parcel.ui.theme.Corners
import com.xxxx.parcel.util.CompartmentCode
import com.xxxx.parcel.util.PickupRoute
import com.xxxx.parcel.util.PickupZone
import com.xxxx.parcel.util.RouteOptions
import com.xxxx.parcel.util.RouteStop
import com.xxxx.parcel.util.addCompletedIds
import com.xxxx.parcel.util.effectiveCompartmentNumber
import com.xxxx.parcel.util.parseCompartmentCode
import com.xxxx.parcel.util.planPickupRoute
import com.xxxx.parcel.util.removeCompletedId
import com.xxxx.parcel.viewmodel.ParcelViewModel

/** 行程里的一步。 */
enum class TripStepKind { PICKUP, SF_CHECKOUT, EXIT }

/**
 * 行程的一步：取件 / 顺丰出库 / 出站。
 *
 * [stopIndex] 是它在 `route.stops` 里的下标；**已出库之后**顺丰出库点已不在路线里，
 * 那一步的 [stopIndex] 为 -1（`sfDone = true`），地图保持在上一个真实停靠点。
 */
data class TripStep(
    val kind: TripStepKind,
    val stopIndex: Int,
    val code: String? = null,
    val sfDone: Boolean = false,
)

/**
 * **整段行程**的展示状态：**含已经取掉的**，顺序**冻结**（来自路线本身）。
 *
 * 用户 2026-10-01：
 * - 「点一下从『当前 1/50』变『当前 2/50』」⇒ 用「全部快递站取件码」一次性规划，标记已取不重排、不改分母；
 * - 「顺丰出库 / 出站要作为卡片**插在序列里**（轮到它时地图正好显示去顺丰的路）」⇒
 *   [steps] 直接按 `route.stops` 顺序生成，顺丰出库点的位置由规划器（Held–Karp）在
 *   「所有 S 件之后」的合法位置里挑最优，**保底在出站之前**；
 * - 已出库后那一步仍然保留（灰掉、可撤销），不会凭空消失。
 */
data class TripView(
    val route: PickupRoute,
    val completed: Set<String>,
    val byCompartment: Map<CompartmentCode, SmsData>,
    /** 按路线顺序的全部步骤（取件 + 顺丰出库 + 出站） */
    val steps: List<TripStep>,
) {
    val pickupTotal: Int get() = steps.count { it.kind == TripStepKind.PICKUP }

    val hasSfCodes: Boolean
        get() = steps.any { it.kind == TripStepKind.PICKUP && parseCompartmentCode(it.code.orEmpty())?.zone == PickupZone.SF }

    /** 该步是第几件取件（1 起）；非取件步返回 null */
    fun pickupNo(stepIndex: Int): Int? {
        if (stepIndex !in steps.indices) return null
        if (steps[stepIndex].kind != TripStepKind.PICKUP) return null
        return steps.take(stepIndex + 1).count { it.kind == TripStepKind.PICKUP }
    }

    /**
     * 第一个**需要动手**的步（按路线顺序）：还没取的取件，或还没出库的顺丰出库。
     * 都做完了 ⇒ 停在出站那一步（地图这时正好显示去出口的路）。
     */
    fun firstPendingStep(): Int {
        val action = steps.indexOfFirst { s ->
            (s.kind == TripStepKind.PICKUP && s.code !in completed) ||
                (s.kind == TripStepKind.SF_CHECKOUT && !s.sfDone)
        }
        if (action >= 0) return action
        val exit = steps.indexOfFirst { it.kind == TripStepKind.EXIT }
        return if (exit >= 0) exit else 0
    }

    /** 把「用户选中的那一步」夹到合法范围 */
    fun clampCurrent(selected: Int?): Int =
        selected?.takeIf { it in steps.indices } ?: firstPendingStep()

    /** 这一步对应的地图站下标；已出库的顺丰步（-1）退回「前一个真实站」 */
    fun mapStopIndex(stepIndex: Int): Int {
        val s = steps.getOrNull(stepIndex) ?: return 0
        if (s.stopIndex >= 0) return s.stopIndex
        val prev = steps.take(stepIndex).map { it.stopIndex }.lastOrNull { it >= 0 }
        return prev ?: route.stops.lastIndex.coerceAtLeast(0)
    }
}

/** 由短信列表算出 [TripView]（地图取件页与首页全屏地图共用同一套口径）。 */
@Composable
fun rememberTripView(
    successData: List<SmsData>,
    options: RouteOptions,
    /** 顺丰是否已出库（用户点过「顺丰出库」卡片）⇒ 路线不再绕出库机，但那一步仍保留（灰掉可撤销） */
    sfCheckedOut: Boolean = false,
): TripView {
    val tripCodes = remember(successData) {
        successData
            .map { effectiveCompartmentNumber(it.compartmentNumber, it.code) }
            .filter { it.isNotBlank() }
            .distinct()
    }
    val route = remember(tripCodes, options, sfCheckedOut) {
        planPickupRoute(rawCodes = tripCodes, options = options, sfCheckedOut = sfCheckedOut)
    }
    val completed = remember(successData) {
        successData
            .filter { it.isCompleted }
            .map { effectiveCompartmentNumber(it.compartmentNumber, it.code) }
            .toSet()
    }
    val byCompartment = remember(successData) {
        buildMap {
            successData.forEach { s ->
                parseCompartmentCode(effectiveCompartmentNumber(s.compartmentNumber, s.code))?.let { put(it, s) }
            }
        }
    }
    val steps = remember(route, completed, sfCheckedOut) {
        buildList {
            route.stops.forEachIndexed { i, s ->
                when (s) {
                    is RouteStop.Pickup -> add(TripStep(TripStepKind.PICKUP, i, s.code.toString()))
                    RouteStop.SfCheckout -> add(TripStep(TripStepKind.SF_CHECKOUT, i))
                    is RouteStop.Exit -> add(TripStep(TripStepKind.EXIT, i))
                }
            }
            // 已出库 ⇒ 路线里不再有顺丰出库点，但**这一步仍然保留在序列里**（灰掉、可撤销），
            // 位置 = 最后一个 S 取件点之后（没有 S 就放最前面）。
            if (sfCheckedOut) {
                val hasSfPick = any { it.kind == TripStepKind.PICKUP && parseCompartmentCode(it.code.orEmpty())?.zone == PickupZone.SF }
                if (hasSfPick && none { it.kind == TripStepKind.SF_CHECKOUT }) {
                    val after = indexOfLast { it.kind == TripStepKind.PICKUP && parseCompartmentCode(it.code.orEmpty())?.zone == PickupZone.SF }
                    add(after + 1, TripStep(TripStepKind.SF_CHECKOUT, -1, sfDone = true))
                }
            }
        }
    }
    return remember(route, completed, byCompartment, steps) {
        TripView(route, completed, byCompartment, steps)
    }
}

/**
 * 行程顶部整体：**当前这一步的卡**（取件 / 顺丰出库 / 出站）+ 下方横向可滑的整段序列。
 *
 * 顺丰出库与出站**就是序列里的两张卡**（不是另外挂在下面）：轮到顺丰出库那一步时，
 * 地图的高亮正好是「去顺丰专用闸机」的那一段（用户 2026-10-01）。
 */
@Composable
fun TripStopSection(
    context: Context,
    viewModel: ParcelViewModel,
    view: TripView,
    current: Int?,
    onCurrentChange: (Int?) -> Unit,
    modifier: Modifier = Modifier,
    /** 顺丰是否已出库（状态由调用方持有；点顺丰卡可切换） */
    sfCheckedOut: Boolean = false,
    onToggleSfDone: () -> Unit = {},
    /** 「N 件待出库」提醒（菜单里可关） */
    showSfCount: Boolean = false,
    sfPendingCount: Int = 0,
    /** 点「出站」卡：全屏出示条码 */
    onShowBarcode: () -> Unit = {},
) {
    val cur = view.clampCurrent(current)
    val curStep = view.steps.getOrNull(cur)
    val addressOf: (String) -> String = { code ->
        view.byCompartment[parseCompartmentCode(code)]?.address.orEmpty()
    }

    // 点击：
    //  取件 ⇒ 标记**这一件**已取（再点恢复）；顺丰出库 ⇒ 已出库（再点撤销）；出站 ⇒ 出示取件码
    val toggle: (TripStep) -> Unit = { step ->
        when (step.kind) {
            TripStepKind.EXIT -> onShowBarcode()
            TripStepKind.SF_CHECKOUT -> onToggleSfDone()
            TripStepKind.PICKUP -> {
                val code = step.code.orEmpty()
                val target = parseCompartmentCode(code)?.let { view.byCompartment[it] }
                when {
                    target == null -> Unit
                    target.isCompleted -> removeCompletedId(context, viewModel, target.sms, target.code)
                    else -> {
                        addCompletedIds(context, viewModel, listOf(target.sms), listOf(target.code))
                        // **按路线顺序前进**：下一件还没取的取件，或（没出库时）顺丰出库那一步。
                        // 🔴 用户 2026-10-01：「轮到顺丰出库这张卡片的时候，地图上正好显示去顺丰的路」
                        //    ⇒ 取完最后一件 S 件后，当前步应当落在**顺丰出库**那一步（而不是跳去下一件普通件）。
                        val nowDone = view.completed + code
                        val from = cur
                        val nextAction = ((from + 1) until view.steps.size).firstOrNull { s ->
                            val st = view.steps[s]
                            (st.kind == TripStepKind.PICKUP && st.code !in nowDone) ||
                                (st.kind == TripStepKind.SF_CHECKOUT && !st.sfDone)
                        }
                        val exitStep = view.steps.indexOfFirst { it.kind == TripStepKind.EXIT }
                            .takeIf { it >= 0 }
                        onCurrentChange(nextAction ?: exitStep ?: from)
                    }
                }
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        TripStepCard(
            step = curStep,
            currentIndex = cur,
            stepCount = view.steps.size,
            pickupNo = view.pickupNo(cur),
            pickupTotal = view.pickupTotal,
            done = when (curStep?.kind) {
                TripStepKind.PICKUP -> curStep.code?.let { it in view.completed } == true
                TripStepKind.SF_CHECKOUT -> sfCheckedOut
                else -> false
            },
            addressOf = addressOf,
            legTiles = view.route.legs.getOrNull(view.mapStopIndex(cur))?.tiles,
            totalTiles = view.route.totalTiles,
            exitLabel = view.route.exit.label,
            sfCount = if (showSfCount && !sfCheckedOut) sfPendingCount else null,
            onJump = { onCurrentChange(it) },
            onTap = { curStep?.let(toggle) },
        )
        TripStrip(
            steps = view.steps,
            current = cur,
            completed = view.completed,
            sfCheckedOut = sfCheckedOut,
            onJump = { onCurrentChange(it) },
        )
    }
}

/** 当前这一步的卡（取件 / 顺丰出库 / 出站三种版式）。 */
@Composable
private fun TripStepCard(
    step: TripStep?,
    currentIndex: Int,
    stepCount: Int,
    pickupNo: Int?,
    pickupTotal: Int,
    done: Boolean,
    addressOf: (String) -> String,
    legTiles: Double?,
    totalTiles: Double,
    exitLabel: String,
    sfCount: Int?,
    onJump: (Int) -> Unit,
    onTap: () -> Unit,
) {
    if (step == null) return
    var dragX by remember(step) { mutableFloatStateOf(0f) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .pointerInput(step) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        when {
                            dragX <= -60f && currentIndex < stepCount - 1 -> onJump(currentIndex + 1)
                            dragX >= 60f && currentIndex > 0 -> onJump(currentIndex - 1)
                        }
                        dragX = 0f
                    },
                    onDragCancel = { dragX = 0f },
                    onHorizontalDrag = { _, delta -> dragX += delta },
                )
            }
            .clickable(onClick = onTap),
        shape = Corners.cardShape,
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    when (step.kind) {
                        TripStepKind.PICKUP -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "当前 ${pickupNo ?: 1}/$pickupTotal",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                                if (done) {
                                    Text(
                                        "已取",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline,
                                        modifier = Modifier
                                            .padding(start = 6.dp)
                                            .clip(Corners.chipShape)
                                            .background(MaterialTheme.colorScheme.surfaceVariant)
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            }
                            Text(
                                text = step.code.orEmpty(),
                                textDecoration = if (done) TextDecoration.LineThrough else TextDecoration.None,
                                color = if (done) {
                                    MaterialTheme.colorScheme.outline
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val address = addressOf(step.code.orEmpty())
                            if (address.isNotBlank()) {
                                Text(
                                    address,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }

                        TripStepKind.SF_CHECKOUT -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("顺丰出库", style = MaterialTheme.typography.labelMedium, color = Color(0xFFE65100))
                                if (done) {
                                    Text(
                                        "已出库 ✓",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF1B8A2E),
                                        modifier = Modifier
                                            .padding(start = 6.dp)
                                            .clip(Corners.chipShape)
                                            .background(Color(0xFFE7F6E9))
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            }
                            Text(
                                text = "顺丰专用闸机",
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFE65100),
                                maxLines = 1,
                            )
                            Text(
                                text = if (done) "已经出库了；点击可撤销" else "取了顺丰件先在这里出库；这台不能出站",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }

                        TripStepKind.EXIT -> {
                            Text("出站", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                            Text(
                                text = exitLabel,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF39414D),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text("点击出示取件码", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (step.kind == TripStepKind.SF_CHECKOUT && sfCount != null && sfCount > 0) {
                    Box(
                        modifier = Modifier
                            .clip(Corners.chipShape)
                            .background(Color(0xFFE65100))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = "$sfCount 件待出库",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                        )
                    }
                }
            }
            if (legTiles != null) {
                Text(
                    "本段 ${fmtTiles(legTiles)} 格 · 全程 ${fmtTiles(totalTiles)} 格",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Text(
                when (step.kind) {
                    TripStepKind.PICKUP -> when {
                        done -> "再次点击可恢复为未取件"
                        else -> "点击标记已取 · 左右滑切换 · 下方可翻看整段"
                    }
                    TripStepKind.SF_CHECKOUT -> if (done) "已出库 ✓" else "点击表示已出库"
                    TripStepKind.EXIT -> "出库 ≠ 出站；走到这里才结束"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** 整段序列（横向可滑）：取件写「序号 · 取件码」，顺丰出库写 `SF`、出站写 `出`。 */
@Composable
private fun TripStrip(
    steps: List<TripStep>,
    current: Int,
    completed: Set<String>,
    sfCheckedOut: Boolean,
    onJump: (Int) -> Unit,
) {
    if (steps.isEmpty()) return
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    LaunchedEffect(current, steps.size) {
        if (current in steps.indices) {
            val approx = with(density) { (current * 78).dp.roundToPx() }
            scroll.animateScrollTo(approx.coerceIn(0, scroll.maxValue))
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .horizontalScroll(scroll),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        var pickupSeen = 0
        steps.forEachIndexed { i, step ->
            val isCur = i == current
            val done = when (step.kind) {
                TripStepKind.PICKUP -> {
                    pickupSeen += 1
                    step.code in completed
                }
                TripStepKind.SF_CHECKOUT -> sfCheckedOut
                TripStepKind.EXIT -> false
            }
            val text = when (step.kind) {
                TripStepKind.PICKUP -> "$pickupSeen · ${step.code}"
                TripStepKind.SF_CHECKOUT -> if (done) "SF ✓" else "SF"
                TripStepKind.EXIT -> "出"
            }
            Text(
                text = text,
                textDecoration = if (done) TextDecoration.LineThrough else TextDecoration.None,
                color = when {
                    isCur -> MaterialTheme.colorScheme.onPrimary
                    done -> MaterialTheme.colorScheme.outline
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                modifier = Modifier
                    .clip(Corners.chipShape)
                    .background(
                        when {
                            isCur -> MaterialTheme.colorScheme.primary
                            done -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        }
                    )
                    .clickable { onJump(i) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

private fun fmtTiles(value: Double): String {
    val rounded = kotlin.math.round(value * 10.0) / 10.0
    return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
}
