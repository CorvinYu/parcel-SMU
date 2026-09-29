package com.xxxx.parcel.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.xxxx.parcel.util.BarcodeSymbology
import com.xxxx.parcel.util.renderBarcodePixels
import com.xxxx.parcel.viewmodel.ParcelViewModel

/**
 * 条码试验（**隐藏的实测工具页**，不做正式功能）。
 *
 * ## 为什么要它
 *
 * 用户设想：快递柜柜机上有个「条码扫描窗口」，如果把取件码渲染成条码给它扫，就能省掉手动输码。
 *
 * 调研结论（2026-09-29）是**不乐观的**：丰巢官网的取件方式只有「一键开柜（App 联网）」、
 * 「输码取件（键盘输入短信取件码）」、「忘记取件码（手机号+验证码）」三条，
 * **没有柜机扫用户取件码这条路径**；柜门上那个条码扫描在存件侧是给快递员扫运单条码用的；
 * App 里的取件二维码是平台在线签发的**动态码**，而我们刻意不联网、也拿不到。
 *
 * ⇒ 所以只能**逐台实测**：同一台柜机认不认，只有拿到跟前试才知道。
 * 本页把同一个取件码按多种码制渲染出来，供现场试扫；能扫开的机型再针对它做正式功能。
 *
 * 注意：一维码的「码制 + 内容长度 + 校验位」不同厂商要求不一，所以这里把**实际编码进去的内容**
 * 也一并显示出来，方便对照柜机的提示。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BarcodeLabScreen(
    context: Context,
    viewModel: ParcelViewModel,
    navController: NavController,
) {
    val successData by viewModel.successSmsData.collectAsState()
    // 候选：当前未取件的取件码 + 解析出的货格号，去重
    val candidates = remember(successData) {
        successData.filter { !it.isCompleted }
            .flatMap { listOf(it.code, it.compartmentNumber) }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }
    var input by remember(candidates) { mutableStateOf(candidates.firstOrNull().orEmpty()) }
    val variants = remember(input) { labVariants(input) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("条码试验") },
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
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("这是什么", fontWeight = FontWeight.Medium)
                    Text(
                        "把同一个取件码按几种常见码制渲染出来，拿到快递柜柜机的「扫码口」上试扫，" +
                            "看有没有机型认。**成功与否取决于那台柜机的固件**，测出来才知道。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text("怎么测", fontWeight = FontWeight.Medium)
                    Text(
                        "在柜机上点「取件」→ 如果屏幕出现扫码提示（或扫码口有光），就把下面对应的条码" +
                            "凑近扫一下；注意先把手机亮度调高、别贴反光。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "已查过：丰巢官方取件只有「一键开柜 / 输码取件 / 忘记取件码」三条，" +
                            "没有柜机扫取件码这条路 ⇒ 扫不开是正常的，别当成 bug。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("要试的取件码") },
                supportingText = { Text("一般用快递柜的纯数字取件码；也可以用人工货架的货格号试试。") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (candidates.isNotEmpty()) {
                Text("从当前待取件里选", fontWeight = FontWeight.Medium)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    candidates.chunked(3).forEach { rowCodes ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            rowCodes.forEach { code ->
                                AssistChip(
                                    onClick = { input = code },
                                    label = { Text(code) },
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider()
            Text("各码制渲染结果", fontWeight = FontWeight.Medium)

            if (variants.isEmpty()) {
                Text("先填一个取件码", style = MaterialTheme.typography.bodySmall)
            } else {
                variants.forEach { variant -> LabVariantCard(variant) }
            }

            Text(
                "说明：一维码纵向拉伸不影响识别（条宽不变），所以这里的条码可以拉高显眼；" +
                    "二维码必须等比，否则会变形扫不出。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 一种码制 + 实际编码进去的内容。 */
internal data class LabVariant(
    val symbology: BarcodeSymbology,
    val payload: String,
    val note: String,
)

/**
 * 把用户输入的取件码整理成各码制**能编码**的内容。
 *
 * 一维码对内容有要求（Code39 只认大写与少数符号、ITF 只认偶数位数字、EAN-13 只认 12/13 位数字），
 * 所以这里做必要的变换并把变换结果写出来——否则用户会以为「码是对的、柜机不认」，
 * 其实是我们编出来的内容不是那个格式。
 */
internal fun labVariants(raw: String): List<LabVariant> {
    val code = raw.trim()
    if (code.isEmpty()) return emptyList()
    val list = mutableListOf<LabVariant>()

    list += LabVariant(BarcodeSymbology.CODE_128, code, "原样编码（Code128 对内容最宽容，优先试这个）")

    val code39 = code.uppercase().filter { it.isDigit() || it in 'A'..'Z' || it in "-. $/+%" }
    if (code39.isNotEmpty()) {
        list += LabVariant(
            BarcodeSymbology.CODE_39,
            code39,
            if (code39 == code.uppercase()) "原样（大写）" else "Code39 不支持的字符已过滤 ⇒ 实际内容：$code39",
        )
    }

    val digits = code.filter { it.isDigit() }
    if (digits.isNotEmpty()) {
        val itf = if (digits.length % 2 == 1) "0$digits" else digits
        list += LabVariant(
            BarcodeSymbology.ITF,
            itf,
            if (itf == code) "原样（纯数字、偶数位）" else "转成纯数字并补齐偶数位 ⇒ 实际内容：$itf",
        )

        // EAN-13：给 12 位内容，校验位交给编码器算（自己填第 13 位很容易算错导致编不出来）
        val twelve = digits.takeLast(12).padStart(12, '0')
        val full13 = twelve + ean13CheckDigit(twelve)
        list += LabVariant(
            BarcodeSymbology.EAN_13,
            twelve,
            "取后 12 位补零、校验位自动计算 ⇒ 实际条码是 $full13",
        )
    }

    list += LabVariant(BarcodeSymbology.QR_CODE, code, "二维码（若柜机支持「反向扫码」才可能认）")
    return list
}

/** EAN-13 校验位（奇位×1、偶位×3）。 */
internal fun ean13CheckDigit(twelve: String): Char {
    var sum = 0
    twelve.forEachIndexed { index, c ->
        val digit = c - '0'
        sum += digit * if (index % 2 == 0) 1 else 3
    }
    return ('0' + ((10 - sum % 10) % 10))
}

@Composable
private fun LabVariantCard(variant: LabVariant) {
    val isQr = variant.symbology == BarcodeSymbology.QR_CODE
    val widthPx = if (isQr) 600 else 1200
    val heightPx = if (isQr) 600 else 320
    val pixels = remember(variant, widthPx, heightPx) {
        renderBarcodePixels(variant.payload, variant.symbology, widthPx, heightPx)
    }
    val bitmap = remember(pixels) {
        pixels?.let {
            Bitmap.createBitmap(it.pixels, it.width, it.height, Bitmap.Config.ARGB_8888)
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(variant.symbology.label, fontWeight = FontWeight.Medium, fontSize = 16.sp)
            Text(
                text = "实际内容：${variant.payload}",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(variant.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (isQr) 200.dp else 96.dp)
                    .background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                if (bitmap == null) {
                    Text(
                        text = "这个内容用 ${variant.symbology.label} 编不出来",
                        color = Color(0xFFAA0000),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = variant.symbology.label,
                        modifier = Modifier.fillMaxSize(),
                        // 一维码可纵向拉伸；二维码必须等比
                        contentScale = if (isQr) ContentScale.Fit else ContentScale.FillBounds,
                    )
                }
            }
        }
    }
}
