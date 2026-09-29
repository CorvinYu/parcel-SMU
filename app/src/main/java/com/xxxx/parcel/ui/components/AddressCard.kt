package com.xxxx.parcel.ui.components

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.xxxx.parcel.model.ParcelData
import com.xxxx.parcel.model.SmsData
import com.xxxx.parcel.util.PickupPlace
import com.xxxx.parcel.util.addCompletedIds
import com.xxxx.parcel.util.classifyPickupPlace
import com.xxxx.parcel.util.formatPickupCode
import com.xxxx.parcel.util.isBarcodeBackgroundEnabled
import com.xxxx.parcel.util.removeCompletedId
import com.xxxx.parcel.viewmodel.ParcelViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@SuppressLint("MutableCollectionMutableState")
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AddressCard(
    context: Context,
    viewModel: ParcelViewModel,
    navController: NavController,
    updateAllWidget: () -> Unit,
    showCompleted: Boolean,
    showCodeTime: Boolean,
    showCompartment: Boolean = true,
    parcelData: ParcelData,
    expandedStates: androidx.compose.runtime.MutableState<MutableMap<String, Boolean>>,
    isExpanded: Boolean,
    preferLockerAddress: Boolean,
    isSeniorMode: Boolean,
    isTimeSort: Boolean = false,
    codeNotes: Map<String, String> = emptyMap(),
    onLongPressCode: (SmsData) -> Unit = {},
    /** 列表已按「快递柜」分组时，卡片标题就不用再重复「· 自助取件」了 */
    showLockerTag: Boolean = true,
    /** 隐藏整行地址头（含 + 与整组勾选按钮）—— 快递站那种短信碎片地址用 */
    hideHeader: Boolean = false,
    /** 「按取件路线排序」时的取件序号（1 起）；null 表示不显示 */
    routeOrder: Int? = null,
) {
    val isAllCompleted = parcelData.smsDataList.find { !it.isCompleted } == null
    val barcodeBackgroundOn = remember { isBarcodeBackgroundEnabled(context) }
    // 默认分类：纯数字取件码 ⇒ 快递柜（自助取件）；含字母 ⇒ 快递站（人工货架，含顺丰 S、大件 Y）
    val isLockerCard = remember(parcelData) {
        parcelData.smsDataList.any { classifyPickupPlace(it.code) == PickupPlace.LOCKER }
    }
    // 时间排序：取件码按短信时间倒序；默认排序：有柜号的靠前、柜号升序、再按取件码
    val displaySmsDataList = if (isTimeSort) {
        parcelData.smsDataList.sortedByDescending { it.sms.timestamp }
    } else {
        parcelData.smsDataList
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .padding(horizontal = 0.dp),
    ) {
        // 快递站的地址就是短信碎片（「可凭S1-5-2871到店海事大学快递中心店提取」），整行隐藏
        if (!hideHeader) Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                IconButton(
                    modifier = Modifier.size(if (isSeniorMode) 48.dp else 32.dp),
                    onClick = {
                        navController.navigate("add_custom_sms/${parcelData.address}")
                    }
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = "添加自定义取件码",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = "${parcelData.address}（${parcelData.num}）" +
                        if (isLockerCard && showLockerTag) " · 自助取件" else "",
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            expandedStates.value = expandedStates.value.toMutableMap().apply {
                                put(parcelData.address, !isExpanded)
                            }
                        }
                )
            }

            IconButton(
                modifier = Modifier.size(if (isSeniorMode) 48.dp else 36.dp),
                onClick = {
                    if (parcelData.num > 0) {
                        val smsList = parcelData.smsDataList
                            .filterNot { it.isCompleted }
                            .map { it.sms }
                        addCompletedIds(context, viewModel, smsList)
                        updateAllWidget()
                    }
                },
                enabled = parcelData.num > 0
            ) {
                Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = "标记取件",
                    tint = if (parcelData.num > 0) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline
                )
            }
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = if (showCompleted) (isExpanded || !isAllCompleted) else (!isAllCompleted),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                // 隐藏了地址头时就不再需要上面那条空隙，避免叠出一层多余留白
                Spacer(modifier = Modifier.height(if (hideHeader) 2.dp else 4.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        // 条码铺作背景时，卡片默认是全透明的，文字会直接压在条码上难以辨认；
                        // 此时给卡片一张半透明垫子。未开条码背景时保持原样。
                        containerColor = if (barcodeBackgroundOn) {
                            if (isSystemInDarkTheme()) Color.Black.copy(alpha = 0.72f)
                            else Color.White.copy(alpha = 0.88f)
                        } else {
                            Color.Transparent
                        }
                    ),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                        displaySmsDataList.forEachIndexed { rowIndex, smsData ->
                            if (!(((!isExpanded) && smsData.isCompleted) || ((!showCompleted) && smsData.isCompleted))) {

                                Box(modifier = Modifier.padding(vertical = 2.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // 取件路线序号：跟着 ①②③ 取就行（只标在卡片第一行）
                                        if (routeOrder != null && rowIndex == 0) {
                                            Box(
                                                modifier = Modifier
                                                    .padding(end = 8.dp)
                                                    .size(if (isSeniorMode) 44.dp else 30.dp)
                                                    .clip(RoundedCornerShape(50))
                                                    .background(MaterialTheme.colorScheme.primary),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Text(
                                                    text = "$routeOrder",
                                                    color = MaterialTheme.colorScheme.onPrimary,
                                                    fontWeight = FontWeight.Bold,
                                                    style = if (isSeniorMode) {
                                                        MaterialTheme.typography.titleLarge
                                                    } else {
                                                        MaterialTheme.typography.titleMedium
                                                    },
                                                )
                                            }
                                        }
                                        // 快递柜：左侧用大号数字标出柜号，一眼看到去哪个柜
                                        if (classifyPickupPlace(smsData.code) == PickupPlace.LOCKER) {
                                            Box(
                                                modifier = Modifier
                                                    .padding(end = 10.dp)
                                                    .size(if (isSeniorMode) 54.dp else 42.dp)
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(
                                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                                                    ),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Text(
                                                    text = smsData.lockerNumber.ifBlank { "柜" },
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    style = if (isSeniorMode) {
                                                        MaterialTheme.typography.headlineMedium
                                                    } else {
                                                        MaterialTheme.typography.titleLarge
                                                    },
                                                )
                                            }
                                        }

                                        Text(
                                            text = formatPickupCode(smsData.code),
                                            textDecoration = if (smsData.isCompleted) TextDecoration.LineThrough else TextDecoration.None,
                                            color = if (smsData.isCompleted) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.primary,
                                            style = if (isSeniorMode) MaterialTheme.typography.headlineMedium.copy(
                                                fontWeight = FontWeight.Bold
                                            ) else MaterialTheme.typography.titleLarge.copy(
                                                fontWeight = FontWeight.Bold
                                            ),
                                            modifier = Modifier
                                                .weight(1f)
                                                .combinedClickable(
                                                    onClick = {
                                                        if (smsData.isCompleted) {
                                                            removeCompletedId(
                                                                context,
                                                                viewModel,
                                                                smsData.sms
                                                            )
                                                        } else {
                                                            addCompletedIds(
                                                                context,
                                                                viewModel,
                                                                listOf(smsData.sms)
                                                            )
                                                        }
                                                        updateAllWidget()
                                                    },
                                                    onLongClick = { onLongPressCode(smsData) }
                                                )
                                                .padding(0.dp)
                                        )
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            if (!preferLockerAddress && smsData.lockerNumber.isNotEmpty()) {
                                                Text(
                                                    text = if (showCompartment && smsData.compartmentNumber.isNotEmpty())
                                                        "${smsData.lockerNumber}号柜 ${smsData.compartmentNumber}格口"
                                                    else "${smsData.lockerNumber}号柜",
                                                    style = if (isSeniorMode) MaterialTheme.typography.bodyLarge.copy(
                                                        fontWeight = FontWeight.Bold
                                                    ) else MaterialTheme.typography.bodySmall.copy(
                                                        fontWeight = FontWeight.Bold
                                                    ),
                                                    color = if (smsData.isCompleted) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            } else if (showCompartment && smsData.compartmentNumber.isNotEmpty()) {
                                                Text(
                                                    text = "${smsData.compartmentNumber}格口",
                                                    style = if (isSeniorMode) MaterialTheme.typography.bodyLarge.copy(
                                                        fontWeight = FontWeight.Bold
                                                    ) else MaterialTheme.typography.bodySmall.copy(
                                                        fontWeight = FontWeight.Bold
                                                    ),
                                                    color = if (smsData.isCompleted) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            val codeNote = codeNotes[smsData.id] ?: ""
                                            if (codeNote.isNotEmpty()) {
                                                Text(
                                                    text = codeNote,
                                                    style = if (isSeniorMode) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.tertiary,
                                                    textAlign = TextAlign.Center,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            if (showCodeTime) {
                                                val sdf = remember(isSeniorMode) {
                                                    SimpleDateFormat(
                                                        if (isSeniorMode) "MM-dd" else "yyyy-MM-dd HH:mm",
                                                        Locale.getDefault()
                                                    )
                                                }
                                                Text(
                                                    text = sdf.format(Date(smsData.sms.timestamp)),
                                                    style = if (isSeniorMode) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSecondary
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}
