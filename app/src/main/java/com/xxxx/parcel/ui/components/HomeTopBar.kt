package com.xxxx.parcel.ui.components

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.xxxx.parcel.ui.theme.Corners
import com.xxxx.parcel.util.openPddIdentityEntry
import com.xxxx.parcel.util.openTaobaoIdentityEntry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeTopBar(
    context: Context,
    navController: NavController,
    isSeniorMode: Boolean,
    isTimeSort: Boolean,
    /** 首页「快递站」列表是否按最优取件顺序排列 */
    isRouteSort: Boolean = false,
    preferLockerAddress: Boolean,
    isHorizontalLayout: Boolean,
    showCompleted: Boolean,
    showCodeTime: Boolean,
    showCompartment: Boolean,
    currentFilterLabel: String,
    successCount: Int,
    failedCount: Int,
    onFilterClick: () -> Unit,
    onSuccessCountClick: () -> Unit,
    onFailedCountClick: () -> Unit,
    onToggleTimeSort: () -> Unit,
    onToggleRouteSort: () -> Unit = {},
    onTogglePreferLockerAddress: () -> Unit,
    onToggleHorizontalLayout: () -> Unit,
    onToggleShowCompleted: () -> Unit,
    onToggleShowCodeTime: () -> Unit,
    onToggleShowCompartment: () -> Unit,
    onSeniorModeChanged: (Boolean) -> Unit,
    /** 是否显示「地图取件」入口（由「提示与地图（试用）」里的开关控制） */
    mapPageEnabled: Boolean = false,
    onOpenMapPage: () -> Unit = {},
    /** 「地图取件模式」：开启后启动 App 直接进地图页（用户 2026-10-01） */
    onToggleMapPage: () -> Unit = {},
    /** 「顺丰出库件数提醒（测试）」开关状态（用户 2026-10-01，默认关闭） */
    sfCountEnabled: Boolean = false,
    onToggleSfCount: () -> Unit = {},
) {
    TopAppBar(
        title = { },
        navigationIcon = {
            TextButton(
                onClick = onFilterClick,

                ) {
                Text(
                    text = currentFilterLabel,
                    style = if (isSeniorMode) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyLarge
                )
            }
        },
        actions = {
            // 🔴 三个标志（已识别 / 未识别 / 地图）**统一高度 + 统一间距**（用户 2026-10-01：
            //    「他们中间留的空隙不一样，感觉很不和谐」）。之前是两个 Material Button
            //    （内容内边距 2dp、中间隔 16dp）＋ 一个 34dp 圆图标（隔 8dp），三者高矮胖瘦都不一样。
            //    现在：同高 34dp（老人模式 44dp）、同一胶囊形状、彼此固定 8dp。
            val badgeHeight = if (isSeniorMode) Corners.controlSenior else Corners.control
            // 用户 2026-10-01：原来的纯绿 / 品红太「土」⇒ 换成**浅底深字**的柔和色调
            //（暗色模式换成深底浅字），地图仍保持实心蓝 —— 它是主操作入口。
            val dark = isSystemInDarkTheme()
            val okBg = if (dark) Color(0xFF1E4634) else Color(0xFFE3F3E8)
            val okFg = if (dark) Color(0xFF9BE3BD) else Color(0xFF1F6B3B)
            val badBg = if (dark) Color(0xFF4A2E1C) else Color(0xFFFCE8DE)
            val badFg = if (dark) Color(0xFFFFC59B) else Color(0xFF9C4A1B)
            val mapBg = if (dark) Color(0xFF3B62D6) else Color(0xFF2F6FE4)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CountBadge(
                    count = successCount,
                    container = okBg,
                    content = okFg,
                    height = badgeHeight,
                    isSeniorMode = isSeniorMode,
                    onClick = onSuccessCountClick,
                )
                CountBadge(
                    count = failedCount,
                    container = badBg,
                    content = badFg,
                    height = badgeHeight,
                    isSeniorMode = isSeniorMode,
                    onClick = onFailedCountClick,
                )
                // 地图取件的**明显入口**：与两个数字同高的圆底 + 定位图钉
                Box(
                    modifier = Modifier
                        .size(badgeHeight)
                        .clip(Corners.pillShape)
                        .background(mapBg)
                        .clickable(onClick = onOpenMapPage),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Place,
                        contentDescription = "地图取件",
                        tint = Color.White,
                        modifier = Modifier.size(if (isSeniorMode) 26.dp else 20.dp),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            // 顶栏菜单：项目较多，改用**可滚动的底部面板** —— 小屏 / 老人模式下也不会超出屏幕底部
            var showMenu by remember { mutableStateOf(false) }
            IconButton(
                onClick = { showMenu = true },
                modifier = Modifier.size(if (isSeniorMode) 48.dp else 40.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = "菜单",
                    modifier = Modifier.size(if (isSeniorMode) 36.dp else 24.dp)
                )
            }
            if (showMenu) {
                ModalBottomSheet(
                    onDismissRequest = { showMenu = false },
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 620.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = 28.dp),
                    ) {

                    // 「海大版功能」放在菜单最前面（用户要求），「按取件路线排序」是它的第一条
                    SheetSectionTitle("海大版功能")
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (mapPageEnabled) "关闭地图取件模式（启动即开地图页）" else "地图取件模式（启动即开地图页）",
                                style = if (isSeniorMode) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyLarge
                            )
                        },
                        onClick = {
                            showMenu = false
                            onToggleMapPage()
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (isRouteSort) "取消「按取件路线排序」" else "按取件路线排序",
                                style = if (isSeniorMode) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyLarge
                            )
                        },
                        onClick = {
                            showMenu = false
                            onToggleRouteSort()
                        }
                    )
                    // 顺丰出库件数提醒（用户 2026-10-01：先默认关，实机看过后改为**默认开**，菜单可关）
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (sfCountEnabled) {
                                    "关闭顺丰出库件数提醒"
                                } else {
                                    "顺丰出库件数提醒"
                                },
                                style = if (isSeniorMode) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyLarge
                            )
                        },
                        onClick = {
                            showMenu = false
                            onToggleSfCount()
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                "页面背景",
                                style = if (isSeniorMode) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyLarge
                            )
                        },
                        onClick = {
                            showMenu = false
                            navController.navigate("app_background")
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                "快递中心条码",
                                style = if (isSeniorMode) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyLarge
                            )
                        },
                        onClick = {
                            showMenu = false
                            navController.navigate("barcode")
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                "取件路线",
                                style = if (isSeniorMode) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyLarge
                            )
                        },
                        onClick = {
                            showMenu = false
                            navController.navigate("pickup_route")
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                "条码试验（柜机扫取件码）",
                                style = if (isSeniorMode) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyLarge
                            )
                        },
                        onClick = {
                            showMenu = false
                            navController.navigate("barcode_lab")
                        }
                    )

                    SheetSectionTitle("显示与排序")
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (isTimeSort) "切换为默认排序" else "切换为时间排序",
                                style = if (isSeniorMode) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyLarge
                            )
                        },
                        onClick = {
                            showMenu = false
                            onToggleTimeSort()
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (preferLockerAddress) "不优先显示几号柜" else "优先显示几号柜",
                                style = if (isSeniorMode) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyLarge
                            )
                        },
                        onClick = {
                            showMenu = false
                            onTogglePreferLockerAddress()
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (isHorizontalLayout) "切换为纵向地址" else "切换为横向地址",
                                style = if (isSeniorMode) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyLarge
                            )
                        },
                        onClick = {
                            showMenu = false
                            onToggleHorizontalLayout()
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (showCompleted) "隐藏已取件的码" else "显示已取件的码",
                                style = if (isSeniorMode) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyLarge
                            )
                        },
                        onClick = {
                            showMenu = false
                            onToggleShowCompleted()
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (showCompartment) "隐藏格口" else "显示格口",
                                style = if (isSeniorMode) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyLarge
                            )
                        },
                        onClick = {
                            showMenu = false
                            onToggleShowCompartment()
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (showCodeTime) "隐藏时间" else "显示时间",
                                style = if (isSeniorMode) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyLarge
                            )
                        },
                        onClick = {
                            showMenu = false
                            onToggleShowCodeTime()
                        }
                    )
                    SheetSectionTitle("取件与规则")
                    DropdownMenuItem(
                        text = {
                            Text(
                                "添加自定义取件短信",
                                style = if (isSeniorMode) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyLarge
                            )
                        },
                        onClick = {
                            showMenu = false
                            navController.navigate("add_custom_sms/ ")
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                "地址归类",
                                style = if (isSeniorMode) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyLarge
                            )
                        },
                        onClick = {
                            showMenu = false
                            navController.navigate("address_group")
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                "规则列表",
                                style = if (isSeniorMode) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyLarge
                            )
                        },
                        onClick = {
                            showMenu = false
                            navController.navigate("rules")
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                "查看日志",
                                style = if (isSeniorMode) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyLarge
                            )
                        },
                        onClick = {
                            showMenu = false
                            navController.navigate("logs")
                        }
                    )
                    SheetSectionTitle("更多")
                    DropdownMenuItem(
                        text = {
                            Text(
                                "监听第三方app通知",
                                style = if (isSeniorMode) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyLarge
                            )
                        },
                        onClick = {
                            showMenu = false
                            navController.navigate("use_notification")
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                "淘宝身份码",
                                style = if (isSeniorMode) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyLarge
                            )
                        },
                        onClick = {
                            showMenu = false
                            openTaobaoIdentityEntry(context)
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                "拼多多身份码",
                                style = if (isSeniorMode) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyLarge
                            )
                        },
                        onClick = {
                            showMenu = false
                            openPddIdentityEntry(context)
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (isSeniorMode) "关闭老人模式" else "开启老人模式",
                                style = if (isSeniorMode) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyLarge
                            )
                        },
                        onClick = {
                            showMenu = false
                            onSeniorModeChanged(!isSeniorMode)
                        }
                    )
                    if (mapPageEnabled) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "地图取件（地图为主）",
                                    style = if (isSeniorMode) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyLarge
                                )
                            },
                            onClick = {
                                showMenu = false
                                onOpenMapPage()
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = {
                            Text(
                                "关于",
                                style = if (isSeniorMode) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyLarge
                            )
                        },
                        onClick = {
                            showMenu = false
                            navController.navigate("about")
                        }
                    )
                    }
                }
            }

        }
    )
}

/** 底部面板里的分组标题。 */
@Composable
private fun SheetSectionTitle(text: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider()
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
        )
    }
}

/**
 * 顶栏的**计数胶囊**（已识别 / 未识别）。
 *
 * 与地图图标同高（[height]）、同形状（[Corners.pillShape]）、同间距（8dp，由调用方给），
 * 这样三个标志排在一起才整齐（用户 2026-10-01）。
 */
@Composable
private fun CountBadge(
    count: Int,
    container: Color,
    content: Color,
    height: Dp,
    isSeniorMode: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .height(height)
            .widthIn(min = height)
            .clip(Corners.pillShape)
            .background(container)
            .clickable(onClick = onClick)
            .padding(horizontal = if (isSeniorMode) 14.dp else 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = count.toString(),
            color = content,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            style = if (isSeniorMode) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyLarge,
        )
    }
}
