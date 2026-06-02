package com.xxxx.parcel.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.xxxx.parcel.util.AppBackgroundPreset
import com.xxxx.parcel.util.AppBackgroundScaleMode
import com.xxxx.parcel.util.AppBackgroundScope
import com.xxxx.parcel.util.clearAppBackgroundImage
import com.xxxx.parcel.util.getAppBackgroundSettings
import com.xxxx.parcel.util.saveAppBackgroundBlurRadius
import com.xxxx.parcel.util.saveAppBackgroundImage
import com.xxxx.parcel.util.saveAppBackgroundOverlayAlpha
import com.xxxx.parcel.util.saveAppBackgroundPreset
import com.xxxx.parcel.util.saveAppBackgroundScaleMode
import com.xxxx.parcel.util.saveAppBackgroundScope

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AppBackgroundScreen(
    context: Context,
    navController: NavController,
    onSettingsChanged: () -> Unit,
) {
    val initialSettings = remember { getAppBackgroundSettings(context) }
    var imagePath by remember { mutableStateOf(initialSettings.imagePath) }
    var preset by remember { mutableStateOf(initialSettings.preset) }
    var overlayAlpha by remember { mutableFloatStateOf(initialSettings.overlayAlpha) }
    var blurRadius by remember { mutableFloatStateOf(initialSettings.blurRadius) }
    var scaleMode by remember { mutableStateOf(initialSettings.scaleMode) }
    var scope by remember { mutableStateOf(initialSettings.scope) }
    var message by remember { mutableStateOf("建议选择主体居中、明暗分层明显的图片。") }

    val previewBitmap = remember(imagePath) {
        imagePath?.let { path ->
            runCatching {
                val options = BitmapFactory.Options().apply { inSampleSize = 4 }
                BitmapFactory.decodeFile(path, options)
            }.getOrNull()
        }
    }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        when {
            uri == null -> message = "未选择图片"
            saveAppBackgroundImage(context, uri) -> {
                imagePath = getAppBackgroundSettings(context).imagePath
                message = "自定义背景已更新"
                onSettingsChanged()
            }
            else -> message = "保存背景失败，请换一张图片再试"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("页面背景") },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("实时预览", style = MaterialTheme.typography.titleMedium)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                            .background(getPresetBrush(preset), RoundedCornerShape(16.dp))
                            .padding(0.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (previewBitmap != null) {
                            Image(
                                bitmap = previewBitmap.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .blur(blurRadius.dp),
                                contentScale = scaleMode.toContentScale()
                            )
                        }
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .background(Color.Black.copy(alpha = overlayAlpha), RoundedCornerShape(16.dp))
                        )
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("取件码", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                            Text(
                                if (imagePath != null) "自定义图片 + ${preset.label}" else preset.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.88f)
                            )
                        }
                    }
                    Text(
                        "范围：${scope.label}  ·  缩放：${scaleMode.label}  ·  模糊：${blurRadius.toInt()}dp",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("背景来源", style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppBackgroundPreset.entries.forEach { option ->
                            FilterChip(
                                selected = preset == option,
                                onClick = {
                                    preset = option
                                    saveAppBackgroundPreset(context, option)
                                    message = "已切换到 ${option.label}"
                                    onSettingsChanged()
                                },
                                label = { Text(option.label) }
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { imagePicker.launch("image/*") }) {
                            Text(if (imagePath == null) "选择自定义图片" else "重新选择图片")
                        }
                        if (imagePath != null) {
                            TextButton(
                                onClick = {
                                    clearAppBackgroundImage(context)
                                    imagePath = null
                                    message = "已移除自定义图片，保留当前预设背景"
                                    onSettingsChanged()
                                }
                            ) {
                                Text("移除图片")
                            }
                        }
                    }
                    Text(
                        if (imagePath == null) "当前仅使用预设背景。" else "自定义图片会叠加在预设背景之上。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            SettingChipGroup(
                title = "显示范围",
                options = AppBackgroundScope.entries,
                selected = scope,
                onSelected = { option ->
                    scope = option
                    saveAppBackgroundScope(context, option)
                    message = "背景范围已切换为 ${option.label}"
                    onSettingsChanged()
                },
                label = { it.label }
            )

            SettingChipGroup(
                title = "图片缩放",
                options = AppBackgroundScaleMode.entries,
                selected = scaleMode,
                onSelected = { option ->
                    scaleMode = option
                    saveAppBackgroundScaleMode(context, option)
                    message = "图片缩放已切换为 ${option.label}"
                    onSettingsChanged()
                },
                label = { it.label }
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("遮罩强度", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "数值越大，文字越清晰，背景越暗：${(overlayAlpha * 100).toInt()}%",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Slider(
                        value = overlayAlpha,
                        onValueChange = { newValue ->
                            overlayAlpha = newValue
                            saveAppBackgroundOverlayAlpha(context, newValue)
                        },
                        onValueChangeFinished = onSettingsChanged,
                        valueRange = 0.15f..0.85f
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("背景模糊", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "适合纹理复杂或人物主体太靠前的图片：${blurRadius.toInt()}dp",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Slider(
                        value = blurRadius,
                        onValueChange = { newValue ->
                            blurRadius = newValue
                            saveAppBackgroundBlurRadius(context, newValue)
                        },
                        onValueChangeFinished = onSettingsChanged,
                        valueRange = 0f..24f
                    )
                }
            }

            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> SettingChipGroup(
    title: String,
    options: Iterable<T>,
    selected: T,
    onSelected: (T) -> Unit,
    label: (T) -> String,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { option ->
                    FilterChip(
                        selected = selected == option,
                        onClick = { onSelected(option) },
                        label = { Text(label(option)) }
                    )
                }
            }
        }
    }
}

private fun AppBackgroundScaleMode.toContentScale(): ContentScale {
    return when (this) {
        AppBackgroundScaleMode.CROP -> ContentScale.Crop
        AppBackgroundScaleMode.FIT -> ContentScale.Fit
        AppBackgroundScaleMode.FILL -> ContentScale.FillBounds
    }
}

private fun getPresetBrush(preset: AppBackgroundPreset): Brush {
    return when (preset) {
        AppBackgroundPreset.DEFAULT -> Brush.linearGradient(
            colors = listOf(Color(0xFFCEB6F6), Color(0xFFF6F3F4), Color(0xFFF6C8D8))
        )
        AppBackgroundPreset.SUNSET -> Brush.linearGradient(
            colors = listOf(Color(0xFFFFD6A5), Color(0xFFFFADAD), Color(0xFFFDFFB6))
        )
        AppBackgroundPreset.MINT -> Brush.linearGradient(
            colors = listOf(Color(0xFFD8F3DC), Color(0xFFF1FAEE), Color(0xFFB7E4C7))
        )
        AppBackgroundPreset.NIGHT -> Brush.linearGradient(
            colors = listOf(Color(0xFFCDE7FF), Color(0xFFE8D7FF), Color(0xFFF9F7FF))
        )
    }
}
