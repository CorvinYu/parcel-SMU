package com.xxxx.parcel.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.xxxx.parcel.ui.components.BarcodeImage
import com.xxxx.parcel.ui.components.BarcodePresentationDialog
import com.xxxx.parcel.util.BarcodeSymbology
import com.xxxx.parcel.util.clearBarcodePayload
import com.xxxx.parcel.util.decodeBarcodeFromUri
import com.xxxx.parcel.util.getBarcodePayload
import com.xxxx.parcel.util.getBarcodeSymbology
import com.xxxx.parcel.util.getBarcodeUpdatedAt
import com.xxxx.parcel.util.isBarcodeBackgroundEnabled
import com.xxxx.parcel.util.isBarcodeBottomEnabled
import com.xxxx.parcel.util.isBarcodeStripEnabled
import com.xxxx.parcel.util.saveBarcodeBackgroundEnabled
import com.xxxx.parcel.util.saveBarcodeBottomEnabled
import com.xxxx.parcel.util.saveBarcodePayload
import com.xxxx.parcel.util.saveBarcodeStripEnabled
import com.xxxx.parcel.util.saveBarcodeSymbology
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BarcodeScreen(
    context: Context,
    navController: NavController,
    onSettingsChanged: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    var payload by remember { mutableStateOf(getBarcodePayload(context).orEmpty()) }
    var symbology by remember { mutableStateOf(getBarcodeSymbology(context)) }
    var stripEnabled by remember { mutableStateOf(isBarcodeStripEnabled(context)) }
    var bottomEnabled by remember { mutableStateOf(isBarcodeBottomEnabled(context)) }
    var backgroundEnabled by remember { mutableStateOf(isBarcodeBackgroundEnabled(context)) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var showPresentation by remember { mutableStateOf(false) }

    val pickImage = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) {
            status = "已取消选择"
            return@rememberLauncherForActivityResult
        }
        busy = true
        status = "正在识别截图中的条码…"
        scope.launch {
            val decoded = withContext(Dispatchers.IO) { decodeBarcodeFromUri(context, uri) }
            busy = false
            if (decoded.isNullOrBlank()) {
                status = "没能在截图里找到条码。可以把截图裁到只剩条码再试，或直接手动输入内容。"
            } else {
                payload = decoded
                saveBarcodePayload(context, decoded)
                status = "识别成功，已保存。请核对下方内容与条形码是否一致。"
                onSettingsChanged()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("快递中心条码") },
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
            Text(
                text = "把微信里「进出快递中心」的条码截图导入，识别一次后会重新绘制成清晰条码，随时出示。",
                style = MaterialTheme.typography.bodyMedium,
            )

            OutlinedButton(
                onClick = { pickImage.launch("image/*") },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (busy) "识别中…" else "① 导入条码截图并识别")
            }

            if (status.isNotBlank()) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            OutlinedTextField(
                value = payload,
                onValueChange = {
                    payload = it
                    status = ""
                },
                label = { Text("条码内容（识别不准时可手动改）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                maxLines = 3,
            )

            Text("码制", style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BarcodeSymbology.entries.take(3).forEach { item ->
                    FilterChip(
                        selected = symbology == item,
                        onClick = {
                            symbology = item
                            saveBarcodeSymbology(context, item)
                            onSettingsChanged()
                        },
                        label = { Text(item.label.substringBefore("（")) },
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BarcodeSymbology.entries.drop(3).forEach { item ->
                    FilterChip(
                        selected = symbology == item,
                        onClick = {
                            symbology = item
                            saveBarcodeSymbology(context, item)
                            onSettingsChanged()
                        },
                        label = { Text(item.label.substringBefore("（")) },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        if (payload.isBlank()) {
                            status = "内容为空，无法保存"
                        } else {
                            saveBarcodePayload(context, payload.trim())
                            saveBarcodeSymbology(context, symbology)
                            status = "已保存"
                            onSettingsChanged()
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("保存") }

                Button(
                    onClick = {
                        if (payload.isBlank()) {
                            status = "内容为空"
                        } else {
                            saveBarcodePayload(context, payload.trim())
                            onSettingsChanged()
                            showPresentation = true
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("出示") }

                OutlinedButton(
                    onClick = {
                        clearBarcodePayload(context)
                        payload = ""
                        status = "已清除"
                        onSettingsChanged()
                    },
                ) { Text("清除") }
            }

            val updatedAt = getBarcodeUpdatedAt(context)
            if (updatedAt > 0) {
                val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                Text(
                    text = "上次更新：" + fmt.format(Date(updatedAt)),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("预览", style = MaterialTheme.typography.titleSmall)
                    if (payload.isBlank()) {
                        Text("（还没有条码内容）", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        BarcodeImage(
                            payload = payload.trim(),
                            symbology = symbology,
                            heightDp = 120,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = "提示：一维条码只能横向等比缩放，本预览与实际出示都已保证这一点。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            Text("显示方式", style = MaterialTheme.typography.titleSmall)

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("首页顶部常驻条码条", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "首页最上方固定显示条码，随时可出示，不遮挡取件卡片。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = stripEnabled,
                    onCheckedChange = {
                        stripEnabled = it
                        saveBarcodeStripEnabled(context, it)
                        onSettingsChanged()
                    }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("底部浮窗", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "条码浮在列表下方：列表短时正好占住底部空白；列表长时会被遮住一部分，点一下即可全屏。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = bottomEnabled,
                    onCheckedChange = {
                        bottomEnabled = it
                        saveBarcodeBottomEnabled(context, it)
                        onSettingsChanged()
                    }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("铺满首页背景", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "把条码放大铺在首页背景上。注意：卡片会压住部分条码，真正扫码建议用「出示」全屏模式。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = backgroundEnabled,
                    onCheckedChange = {
                        backgroundEnabled = it
                        saveBarcodeBackgroundEnabled(context, it)
                        onSettingsChanged()
                    }
                )
            }

            TextButton(onClick = { showPresentation = true }, enabled = payload.isNotBlank()) {
                Text("全屏出示（亮度自动拉满）", fontWeight = FontWeight.Medium, fontSize = 16.sp)
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showPresentation) {
        BarcodePresentationDialog(
            context = context,
            onDismiss = { showPresentation = false },
        )
    }
}
