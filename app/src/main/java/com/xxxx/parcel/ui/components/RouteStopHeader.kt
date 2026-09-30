package com.xxxx.parcel.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xxxx.parcel.ui.theme.Corners
import com.xxxx.parcel.util.PickupRoute
import com.xxxx.parcel.util.RouteStop

/**
 * 「当前取件码」头部卡 —— 与**地图取件页**顶部那块同一套样式。
 *
 * 用户 2026-10-01：首页地图的**全屏页**最上面也要有这么一块，一眼看到现在该取哪一件
 * （原来全屏只有地图，得在地图上找小标签）。
 *
 * 版式：`当前 i/N`（只数取件站，顺丰出库/出站不占号）+ 大号取件码 + 本段/全程格数。
 */
@Composable
fun RouteStopHeader(
    route: PickupRoute,
    stopIndex: Int,
    modifier: Modifier = Modifier,
) {
    val stops = route.stops
    if (stops.isEmpty()) return
    val idx = stopIndex.coerceIn(0, stops.size - 1)
    val stop = stops[idx]
    val isPickup = stop is RouteStop.Pickup
    val pickups = stops.take(idx + 1).count { it is RouteStop.Pickup }
    val total = stops.count { it is RouteStop.Pickup }
    val title = when (stop) {
        is RouteStop.Pickup -> stop.code.toString()
        RouteStop.SfCheckout -> "顺丰出库（顺丰专用闸机）"
        is RouteStop.Exit -> "出站：${stop.kind.label}"
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = Corners.cardShape,
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            if (isPickup) {
                Text(
                    text = "当前 $pickups/$total",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Text(
                text = title,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            route.legs.getOrNull(idx)?.let { leg ->
                Text(
                    text = "本段 ${fmtTiles(leg.tiles)} 格 · 全程 ${fmtTiles(route.totalTiles)} 格",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

private fun fmtTiles(value: Double): String {
    val rounded = kotlin.math.round(value * 10.0) / 10.0
    return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
}
