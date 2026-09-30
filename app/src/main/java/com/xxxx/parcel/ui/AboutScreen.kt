package com.xxxx.parcel.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.navigation.NavController

fun getAppVersionName(context: Context): String {
    try {
        // 获取 PackageManager 实例
        val packageManager = context.packageManager
        // 获取当前应用的包名
        val packageName = context.packageName
        // 获取应用信息，包含版本号等
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        // 返回版本名称
        return ("版本：" + packageInfo.versionName)
    } catch (e: PackageManager.NameNotFoundException) {
        e.printStackTrace()
        return "未知版本"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(navController: NavController) {
    val context = LocalContext.current
    val upstreamUrl = "https://github.com/shareven/parcel"
    val forkUrl = "https://github.com/CorvinYu/parcel-SPU"


    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("关于") },
                navigationIcon = {
                    IconButton(
                        onClick = { navController.navigateUp() },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )

        },
        
    ) {
        innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "当前版本地址",
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            TextButton (
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, forkUrl.toUri())
                    context.startActivity(intent)
                }
            ){
                Text(forkUrl, color = Color(0XFF6200EE) )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "原项目地址",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            TextButton(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, upstreamUrl.toUri())
                    context.startActivity(intent)
                }
            ) {
                Text(upstreamUrl, color = Color(0XFF6200EE))
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(getAppVersionName(context),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(bottom = 16.dp))

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                // 🔴 关于页是纯文本（Compose Text 不解析 Markdown）⇒ **不要写 `**加粗**`/`##`/链接语法**，
                //    否则那些符号会原样露出来（用户 2026-10-01 反馈）。
                text = "这是一个基于原开源项目二次维护的海大版分支（面向上海海事大学快递站场景），" +
                    "不是原作者的官方发布版本。\n\n" +
                    "【取件码】自动解析短信中的地址与取件码，并按取件地点分成三类：快递站（人工货架）、" +
                    "快递柜（自助取件，卡片上显示大号柜号）、校外；可按时间过滤、长按加备注与分享。\n\n" +
                    "【取件路线】按现场平面图建模（照抄你在 Excel 里填的通道），用 Held-Karp 子集 DP 求" +
                    "「取件顺序最优解」（16 件以内为精确最优，更多件走启发式并如实标注）；" +
                    "顺丰件遵循「先去顺丰专用闸机出库、再去普通闸机出库并出站」，出库与出站分开计。" +
                    "首页可按取件路线排序（①②③），支持下拉刷新重排。\n\n" +
                    "【地图取件】独立的「地图取件」页：顶部是当前取件码（点击标记已取、再点恢复；" +
                    "位置显示「当前 i/N」且分母固定），中间是矢量地图（货架旁直接写取件码、有方向指示），" +
                    "底部是条码；首页也可常驻一块路线图示，高度可上下拖动调节。" +
                    "全部取完后，入口进站、顺丰出库、出站三个步骤仍会保留。\n\n" +
                    "【快递中心条码】导入微信截图后离线识别（ZXing，纯 Java，不依赖 Google Play 服务），" +
                    "可常驻在首页顶部、底部浮窗或点击全屏出示（亮度拉满），只有进、出站闸机会用到。" +
                    "另有「条码试验」页，把取件码按五种码制渲染，方便在快递柜柜机上逐台实测。\n\n" +
                    "【其它】桌面卡片（支持暗色模式）、监听第三方 app 通知、老人模式（大字）；" +
                    "保留原项目免费、开源、无广告、不联网的设计方向，不收集任何个人信息。\n\n" +
                    "桌面卡片添加：一般在系统全部卡片、插件或安卓小组件列表中。",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))


            

        }
    }
}
