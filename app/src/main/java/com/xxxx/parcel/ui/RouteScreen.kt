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
import com.xxxx.parcel.util.getRouteRowLetters
import com.xxxx.parcel.util.isRouteReturnToEntrance
import com.xxxx.parcel.util.locate
import com.xxxx.parcel.util.parseCompartmentCode
import com.xxxx.parcel.util.parseRowLetters
import com.xxxx.parcel.util.planPickupRoute
import com.xxxx.parcel.util.saveRouteReturnToEntrance
import com.xxxx.parcel.util.saveRouteRowLetters
import com.xxxx.parcel.viewmodel.ParcelViewModel

/**
 * 取件路线页：把当前待取件按货格号（`D5-23`）排序成一条最短走行路线。
 *
 * 计算由 `planPickupRoute` 完成（纯逻辑，已用暴力枚举验证最优性）。
 * 本页只负责展示与「布局参数」的录入。
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

    val layout = remember(rowLettersText) {
        SiteLayout.default().copy(rowLetters = parseRowLetters(rowLettersText))
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
                            "需要短信里带「货格号」（形如 D5-23）。如果没有，可以在首页长按取件码补" +
                                "备注，或在「添加自定义取件短信」里补上货格号。",
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
                    }
                }

                Text("建议顺序", fontWeight = FontWeight.Medium)
                route.orderedCodes.forEachIndexed { index, code ->
                    val sms = byCode[code]
                    val loc = locate(code, layout)
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
                                if (sms != null) {
                                    if (sms.address.isNotBlank()) {
                                        Text(sms.address, style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (sms.code.isNotBlank()) {
                                        Text("取件码 ${sms.code}", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                if (loc != null) {
                                    Text(
                                        "${if (loc.onLeft) "通道左侧" else "通道右侧"} · " +
                                            "距通道 ${loc.lateralTiles} 格",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
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
                        "最后返回入口：${route.legTiles.last()} 格",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                if (route.unresolved.isNotEmpty()) {
                    HorizontalDivider()
                    Text("需要单独确认的货格号", fontWeight = FontWeight.Medium)
                    Text(
                        route.unresolved.joinToString("、"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "这些属于特殊区（J / S / M）或超出已配置的排范围，本页暂不把它们纳入路线。" +
                            "请在下面把字母序列改对，或到现场核实这两个区域的编号规则。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            HorizontalDivider()
            Text("场地参数", fontWeight = FontWeight.Medium)

            OutlinedTextField(
                value = rowLettersText,
                onValueChange = { rowLettersText = it },
                label = { Text("普通排字母序列（由入口向深处）") },
                supportingText = {
                    Text("用逗号分隔。J / S / M 是特殊区，不要写进来。填错会导致对应件无法定位。")
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        rowLettersText = SiteLayout.default().rowLetters.joinToString(",")
                    },
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
                "其余已确认参数：每排 12 个货架、纵向通道左侧 1~4 右侧 5~12、入口到通道 4 格、" +
                    "相邻两排间隔 1 格。若与现场不符请告知，我再做成可编辑项。",
                style = MaterialTheme.typography.bodySmall,
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}
