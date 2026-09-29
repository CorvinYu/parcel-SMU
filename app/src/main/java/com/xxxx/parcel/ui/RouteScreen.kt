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
import androidx.compose.material3.Switch
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
import com.xxxx.parcel.util.SiteLayout
import com.xxxx.parcel.util.getRouteAisleSpacing
import com.xxxx.parcel.util.getRouteCrossAisle
import com.xxxx.parcel.util.getRouteDoorToSpine
import com.xxxx.parcel.util.getRouteRowLetters
import com.xxxx.parcel.util.isRouteReturnToEntrance
import com.xxxx.parcel.util.locate
import com.xxxx.parcel.util.parseCompartmentCode
import com.xxxx.parcel.util.parseRowLetters
import com.xxxx.parcel.util.planPickupRoute
import com.xxxx.parcel.util.saveRouteAisleSpacing
import com.xxxx.parcel.util.saveRouteCrossAisle
import com.xxxx.parcel.util.saveRouteDoorToSpine
import com.xxxx.parcel.util.saveRouteReturnToEntrance
import com.xxxx.parcel.util.saveRouteRowLetters
import com.xxxx.parcel.viewmodel.ParcelViewModel

/**
 * 取件路线页：把当前待取件按货格号（`D5-23`、`J5-21`、`S3-2-2628`、`Y5-7-1`）排成一条最短走行路线。
 *
 * 场地结构来自用户 2026-09-29 现场踩点（16 排、每两排背靠背共用一条横向通道、主纵向通道在货架 4/5 之间、
 * J 柜列 / S 顺丰区 / Y 大件区）。计算由 `planPickupRoute` 完成（Held–Karp 精确解，已用暴力枚举验证）。
 * 本页只负责展示与「场地参数」校准。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteScreen(
    context: Context,
    viewModel: ParcelViewModel,
    navController: NavController,
) {
    val successData by viewModel.successSmsData.collectAsState()

    var rowLettersText by remember { mutableStateOf(getRouteRowLetters(context).joinToString(",")) }
    var returnToEntrance by remember { mutableStateOf(isRouteReturnToEntrance(context)) }
    var aisleSpacingText by remember { mutableStateOf(getRouteAisleSpacing(context).toString()) }
    var doorToSpineText by remember { mutableStateOf(getRouteDoorToSpine(context).toString()) }
    var crossAisleText by remember { mutableStateOf(getRouteCrossAisle(context).toString()) }

    val layout = remember(rowLettersText, aisleSpacingText, doorToSpineText, crossAisleText) {
        SiteLayout.default().copy(
            rowLetters = parseRowLetters(rowLettersText),
            aisleSpacingTiles = aisleSpacingText.toIntOrNull()?.coerceIn(1, 20) ?: 3,
            doorToSpineTiles = doorToSpineText.toIntOrNull()?.coerceIn(0, 50) ?: 4,
            crossAisleTiles = crossAisleText.toIntOrNull()?.coerceIn(0, 20) ?: 1,
        )
    }
    val pending = remember(successData) {
        successData.filter { !it.isCompleted && it.compartmentNumber.isNotBlank() }
    }
    val route = remember(pending, layout, returnToEntrance) {
        planPickupRoute(
            rawCodes = pending.map { it.compartmentNumber },
            layout = layout,
            returnToEntrance = returnToEntrance,
        )
    }
    val byCode = remember(pending) {
        pending.associateBy { parseCompartmentCode(it.compartmentNumber) }
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
                            "待取 ${route.resolvedCount} 件 · 全程 ${route.totalTiles} 格",
                            fontWeight = FontWeight.Medium,
                            fontSize = 18.sp,
                        )
                        Text(
                            if (route.exact) "顺序为精确最优解" else "件数较多，顺序为启发式近似",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            if (returnToEntrance) "路径：入口 → 逐件取 → 返回入口" else "路径：入口 → 逐件取 → 到最深一件为止",
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
                route.orderedCodes.forEachIndexed { index, code ->
                    val sms = byCode[code]
                    val pos = locate(code, layout)
                    val leg = route.legTiles.getOrNull(index)
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "${index + 1}",
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp,
                                modifier = Modifier.width(32.dp),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(code.toString(), fontWeight = FontWeight.Medium, fontSize = 17.sp)
                                Text(code.zone.label, style = MaterialTheme.typography.bodySmall)
                                if (pos != null) {
                                    Text(pos.label, style = MaterialTheme.typography.bodySmall)
                                }
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
                                Text("→ $leg 格", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }

                if (returnToEntrance && route.legTiles.size == route.resolvedCount + 1) {
                    Text(
                        "最后返回出口：${route.legTiles.last()} 格",
                        style = MaterialTheme.typography.bodyMedium,
                    )
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
                        "这些没能解析成「字母+货架号-格号」。可能是排字母序列填错、货架号越界" +
                            "（主货架 1~12、J 柜列 1~6、S 区 1~3、Y 区 1~8），或短信里本就没有货格号。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            HorizontalDivider()
            Text("场地结构（据 2026-09-29 现场踩点）", fontWeight = FontWeight.Medium)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("纵向：16 排，由入口向里 ${layout.rowLetters.joinToString(" ")}", style = MaterialTheme.typography.bodySmall)
                    Text("横向：每排 12 个货架 —— 主通道西侧 1~4、东侧 5~12", style = MaterialTheme.typography.bodySmall)
                    Text("通道：每两排背靠背共用一条横向通道；主纵向通道在货架 4 与 5 之间", style = MaterialTheme.typography.bodySmall)
                    Text("最里侧通道挂：J 柜列（6 条纵向柜列）、S 顺丰区（3 个货架）", style = MaterialTheme.typography.bodySmall)
                    Text("东侧另有：Y 大件区（y1~y7 + y8 的 8 行 × 3 子位）", style = MaterialTheme.typography.bodySmall)
                    Text("入口在南侧（J39:M40），出口在西侧（A15:A30）", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "分区：主货架区 / J 柜列区 / 顺丰 S 区 / 大件 Y 区　—— 四类都已纳入本页规划。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            HorizontalDivider()
            Text("场地参数（与现场不符时改这里）", fontWeight = FontWeight.Medium)

            OutlinedTextField(
                value = rowLettersText,
                onValueChange = { rowLettersText = it },
                label = { Text("普通排字母序列（由入口向深处）") },
                supportingText = { Text("用逗号分隔。J / S / Y 是特殊区，不要写进来。填错会导致对应件无法定位。") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { rowLettersText = SiteLayout.DEFAULT_ROW_LETTERS.joinToString(",") },
                    modifier = Modifier.weight(1f),
                ) { Text("恢复默认") }

                OutlinedButton(
                    onClick = {
                        val letters = parseRowLetters(rowLettersText)
                        if (letters.isNotEmpty()) {
                            saveRouteRowLetters(context, letters)
                            rowLettersText = letters.joinToString(",")
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("保存") }
            }

            NumberField(
                value = aisleSpacingText,
                onValueChange = { aisleSpacingText = it },
                label = "相邻两条横向通道之间的距离（格）",
                supporting = "默认 3。只影响总格数与「谁更深」的权重，不影响相对顺序。",
                onSave = { saveRouteAisleSpacing(context, it) },
            )
            NumberField(
                value = doorToSpineText,
                onValueChange = { doorToSpineText = it },
                label = "入口到主纵向通道的距离（格）",
                supporting = "默认 4（原图入口在 J39:M40，主通道在 N 列）。对所有件是同一常数。",
                onSave = { saveRouteDoorToSpine(context, it) },
            )
            NumberField(
                value = crossAisleText,
                onValueChange = { crossAisleText = it },
                label = "背靠背两排之间横穿的代价（格）",
                supporting = "默认 1。同一条通道南北两侧可以直接横穿，不必绕回主通道。",
                onSave = { saveRouteCrossAisle(context, it) },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("取完折返回入口", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "关闭则按「敞开路径」算：最优解会把最深的一件排在最后，少走一段回程。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = returnToEntrance,
                    onCheckedChange = {
                        returnToEntrance = it
                        saveRouteReturnToEntrance(context, it)
                    }
                )
            }

            Text(
                "仍是估算、已如实标注的部分：每货架实际格数尚未实测（同一货架内先视作同一点）；" +
                    "J 柜列横向按列序、S/Y 格子横向按格号；a 区排列未确认，只定位到货架号。",
                style = MaterialTheme.typography.bodySmall,
            )

            Spacer(Modifier.height(24.dp))
        }
    }
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
