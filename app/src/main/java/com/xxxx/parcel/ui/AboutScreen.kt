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
                text = "这是一个基于原开源项目二次维护的海大版分支，用于针对上海海事大学快递站场景做持续优化。\n\n当前版本已同步原项目最新功能（长按取件码添加备注、分享，时间过滤，格口显示开关等），并加入页面背景自定义功能，同时保留原项目免费、开源、无广告、不联网的设计方向，不收集任何个人信息。\n\n本 app 会自动解析收到的短信，并从中提取出地址和取件码信息，可以展示到桌面卡片上（支持暗色模式）。您也可以添加自定义规则来改进解析效果。\n\n您可以长按取件码添加备注，分享。\n\n还支持监听第三方 app 通知，自动保存取件码消息，方便在特定校园快递场景下集中查看。打开监听通知权限，还能实现后台进程保活，实时更新桌面卡片。\n\n桌面卡片添加：一般在系统全部卡片、插件或安卓小组件列表中。\n\n原项目作者与原项目仓库信息请以上方链接为准；当前版本不是原作者官方发布版本。",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))


            

        }
    }
}
