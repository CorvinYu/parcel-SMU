package com.xxxx.parcel.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 首页／地图的**圆角与控件尺寸统一口径**。
 *
 * 背景（用户 2026-10-01）：首页各种「胶囊／卡片」的圆角各写一套，看着不和谐 ——
 * 提示条 12、顺丰步骤卡 8（Card 默认）、地图窗格 20、底部条码 20、地图页顶部卡 18、
 * 空态卡 14；顶栏三个标志（已识别 / 未识别 / 地图）尺寸与间距也各不相同（40 / 40 / 34，间距 16 / 8）。
 *
 * ⇒ 全部收敛到下面三个档位，**新代码一律引用这里，不要再就地写数字**：
 *
 * | 档位 | 值 | 用在哪 |
 * |---|---|---|
 * | [card] | 16dp | 大卡片：地图窗格、底部条码卡、地图页顶部卡、空态提示卡 |
 * | [chip] | 12dp | 次级小块：「怎么走」提示条、柜号数字块、地图上取件码小签 |
 * | [pill] | 50% | 胶囊：取件序号、入口／顺丰出库／出站步骤条、地图收起条、顶栏三个标志 |
 *
 * [control] 是**可点控件**的统一高度（顶栏三个标志同高同间距；老人模式用 [controlSenior]）。
 */
object Corners {
    val card: Dp = 16.dp
    val chip: Dp = 12.dp

    /** 顶栏标志 / 小控件的统一高度 */
    val control: Dp = 34.dp
    val controlSenior: Dp = 44.dp

    val cardShape = RoundedCornerShape(card)
    val chipShape = RoundedCornerShape(chip)

    /** 完全圆头（胶囊）。`RoundedCornerShape(50)` = 50% ⇒ 胶囊，不随高度变形。 */
    val pillShape = RoundedCornerShape(50)
}
