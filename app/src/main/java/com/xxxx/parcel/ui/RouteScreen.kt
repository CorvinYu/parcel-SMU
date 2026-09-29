package com.xxxx.parcel.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.xxxx.parcel.util.MAX_EXACT_ITEMS
import com.xxxx.parcel.util.RouteExit
import com.xxxx.parcel.util.RouteOptions
import com.xxxx.parcel.util.RouteStop
import com.xxxx.parcel.util.effectiveCompartmentNumber
import com.xxxx.parcel.util.getRouteJCells
import com.xxxx.parcel.util.parseCompartmentCode
import com.xxxx.parcel.util.planPickupRoute
import com.xxxx.parcel.util.saveRouteJCells
import com.xxxx.parcel.viewmodel.ParcelViewModel

/**
 * 取件路线页。
 *
 * 场地模型**完全来自用户 Excel 的填充色**（可走格 3362 个），场地结构在页面里现场自检：
 * 3 条纵向干线（西侧 F~J / 主通道 AI~AN / 东侧 CK~CP）＋ 9 条横向走廊带 ＋ 3 处闸机带。
 *
 * 顺序由 `planPickupRoute` 求**精确最优**（Held–Karp；已与暴力枚举逐例比对），
 * 并遵守用户的两条顺丰规则：
 * 1. 取了 S 件必须先在`顺丰专用闸机`**出库**（有普通件时出库后继续取，最后从`7个普通闸机`出库并出站）
 * 2. **出库 ≠ 出站**：只有顺丰件时，出库后还要走到`顺丰和无快递出口`**出站**
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteScreen(
    context: Context,
    viewModel: ParcelViewModel,
    navController: NavController,
) {
    val successData by viewModel.successSmsData.collectAsState()

    var jCellsText by remember { mutableStateOf(getRouteJCells(context).toString()) }
    val options = remember(jCellsText) {
        RouteOptions(jCellsPerColumn = jCellsText.toIntOrNull()?.coerceIn(1, 200) ?: 21)
    }
    val pending = remember(successData) {
        // 用「有效货格号」：解析出的货格号为空时，退回「取件码本身就是货格号」
        successData.filter {
            !it.isCompleted && effectiveCompartmentNumber(it.compartmentNumber, it.code).isNotBlank()
        }
    }
    val route = remember(pending, options) {
        planPickupRoute(
            rawCodes = pending.map { effectiveCompartmentNumber(it.compartmentNumber, it.code) },
            options = options,
        )
    }
    val byCode = remember(pending) {
        pending.associateBy {
            parseCompartmentCode(effectiveCompartmentNumber(it.compartmentNumber, it.code))
        }
    }
    val zoneCounts = remember(route) { route.zoneCounts() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("取件路线") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (pending.isEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("还没有可用于规划的取件码", fontWeight = FontWeight.Medium)
                        Text(
                            "需要短信里带「货格号」（形如 D5-23、J5-21、S3-2-2628、Y5-7-1）。" +
                                "如果没有，可以在「添加自定义取件短信」里补上货格号。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            } else {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "待取 ${route.resolvedCount} 件 · 全程 ${fmtTiles(route.totalTiles)} 格",
                            fontWeight = FontWeight.Medium,
                            fontSize = 18.sp,
                        )
                        Text(
                            if (route.exact) "顺序为精确最优解（Held–Karp，≤$MAX_EXACT_ITEMS 件；已与暴力枚举逐例比对）"
                            else "件数 >$MAX_EXACT_ITEMS ⇒ 启发式近似（最近邻 / 走廊扫描两种子 + 2-opt 取优）",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "路径：入口闸机进 → 逐件取 → ${route.exit.label}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (zoneCounts.isNotEmpty()) {
                            Text(
                                zoneCounts.entries.joinToString("　") { "${it.key.label} ${it.value} 件" },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }

                Text("建议顺序", fontWeight = FontWeight.Medium)
                var pickIndex = 0
                route.stops.forEachIndexed { stopIndex, stop ->
                    val leg = route.legTiles.getOrNull(stopIndex)
                    when (stop) {
                        is RouteStop.Pickup -> {
                            pickIndex += 1
                            val code = stop.code
                            val sms = byCode[code]
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "$pickIndex",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 20.sp,
                                        modifier = Modifier.width(32.dp),
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(code.toString(), fontWeight = FontWeight.Medium, fontSize = 17.sp)
                                        Text(code.zone.label, style = MaterialTheme.typography.bodySmall)
                                        Text(stop.spot.label, style = MaterialTheme.typography.bodySmall)
                                        if (sms != null) {
                                            if (sms.address.isNotBlank()) {
                                                Text(sms.address, style = MaterialTheme.typography.bodySmall)
                                            }
                                            if (sms.code.isNotBlank()) {
                                                Text("取件码 ${sms.code}", style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                    }
                                    if (leg != null) {
                                        Text("→ ${fmtTiles(leg)} 格", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }

                        RouteStop.SfCheckout -> {
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("SF", fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.width(32.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("顺丰出库（顺丰专用闸机）", fontWeight = FontWeight.Medium, fontSize = 17.sp)
                                        Text(
                                            "取了 S 件必须在这里出库；这台不能出站" +
                                                (if (route.exit == RouteExit.SF_EXIT) "，出站走到顺丰出口" else "，出库后继续取普通件"),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                        route.sfCheckoutCell?.let {
                                            Text("通道格 ${it.row},${it.col}", style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                    if (leg != null) {
                                        Text("→ ${fmtTiles(leg)} 格", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }

                        is RouteStop.Exit -> {
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("出", fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.width(32.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("出站：${stop.kind.label}", fontWeight = FontWeight.Medium, fontSize = 17.sp)
                                        Text(
                                            if (stop.kind == RouteExit.NORMAL_GATE)
                                                "普通闸机同时是出库口与出站口，出完直接走人"
                                            else
                                                "顺丰出库机不能出站 ⇒ 从顺丰出口离开",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                        route.exitCell?.let {
                                            Text("通道格 ${it.row},${it.col}", style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                    if (leg != null) {
                                        Text("→ ${fmtTiles(leg)} 格", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }

                if (route.lockerCodes.isNotEmpty()) {
                    HorizontalDivider()
                    Text("快递柜（不在人工货架路径上）", fontWeight = FontWeight.Medium)
                    Text(
                        route.lockerCodes.joinToString("、"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "纯数字取件码对应快递柜，与人工货架不是同一套寻址方式。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                if (route.unresolved.isNotEmpty()) {
                    HorizontalDivider()
                    Text("无法定位", fontWeight = FontWeight.Medium)
                    Text(
                        route.unresolved.joinToString("、"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "这些没能对上场地里的货架合并区。可能是货架号越界（主货架 1~12、J 柜列 1~6、" +
                            "S 区 1~3、Y 区 1~8），或短信里本就没有货格号。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            HorizontalDivider()
            Text("场地模型（由你的 Excel 自动生成）", fontWeight = FontWeight.Medium)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("可走格：3362 格 —— 逐格照抄你在 Excel 里给通道填的颜色", style = MaterialTheme.typography.bodySmall)
                    Text("纵向干线 3 条：西侧（靠闸机，列 F~J）／主通道（列 AI~AN）／东侧（列 CK~CP）", style = MaterialTheme.typography.bodySmall)
                    Text("横向走廊 9 条带（含最北 J/S 区那条）；走廊 2 格宽、货架对 1 格", style = MaterialTheme.typography.bodySmall)
                    Text("闸机带：7个普通闸机（出库+出站）／顺丰专用闸机（出库，不能出站）／顺丰和无快递出口（出站）", style = MaterialTheme.typography.bodySmall)
                    Text("路线只走通道格：每段都用网格最短路回溯出来的格序列，结构上不可能穿货架", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "四类分区：主货架区 / J 柜列区 / 顺丰 S 区 / 大件 Y 区 —— 全部纳入规划。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            HorizontalDivider()
            Text("场地参数", fontWeight = FontWeight.Medium)
            NumberField(
                value = jCellsText,
                onValueChange = { jCellsText = it },
                label = "J 柜列每列格数",
                supporting = "货位清单里标着「每列实际格数与北端行号待确认」，实测只到 j5-21（⇒ ≥21 格）。" +
                    "柜列纵深按此值等比铺开（格子 1 在靠通道的外端），并计入走位。",
                onSave = { saveRouteJCells(context, it) },
            )

            Text(
                "仍是近似、已如实标注的部分：每货架实际格数尚未实测（普通排同一货架内不同格先视作同一点）；" +
                    "Y 区在精确版 Excel 里是一整块，只能定位到东侧通道最近点；J 每列格数按上面的值铺开。",
                style = MaterialTheme.typography.bodySmall,
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 瓷砖数显示：整数不带小数点，半格显示一位。 */
private fun fmtTiles(value: Double): String {
    val rounded = kotlin.math.round(value * 10.0) / 10.0
    return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
}

@Composable
private fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    supporting: String,
    onSave: (Int) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.filter { ch -> ch.isDigit() }.take(3)) },
        label = { Text(label) },
        supportingText = { Text(supporting) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        OutlinedButton(onClick = { value.toIntOrNull()?.let(onSave) }) { Text("保存此项") }
    }
}
