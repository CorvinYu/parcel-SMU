package com.xxxx.parcel.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xxxx.parcel.model.ParcelData
import com.xxxx.parcel.model.SmsData
import com.xxxx.parcel.model.SmsModel
import com.xxxx.parcel.util.SmsProcessor
import com.xxxx.parcel.util.SmsParser
import com.xxxx.parcel.util.getAddressMappings
import com.xxxx.parcel.util.getCustomList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ParcelViewModel(
    private val smsParser: SmsParser = SmsParser(),
    private val context: Context? = null
) : ViewModel() {
    // 所有短信列表
    private val _allMessages = MutableStateFlow<List<SmsModel>>(emptyList())

    // 所有已取件id列表
    private val _allCompletedIds = MutableStateFlow<List<String>>(emptyList())

    init {
        context?.let {
            val completedIds = getCustomList(it, "completedIds").toMutableList()
            _allCompletedIds.value = completedIds
        }
    }

    // 解析成功的短信
    private val _successSmsData = MutableStateFlow<List<SmsData>>(emptyList())
    val successSmsData: StateFlow<List<SmsData>> = _successSmsData

    // 解析失败的短信
    private val _failedMessages = MutableStateFlow<List<SmsModel>>(emptyList())
    val failedMessages: StateFlow<List<SmsModel>> = _failedMessages

    // 同一地址的取件码列表
    private val _parcelsData = MutableStateFlow<List<ParcelData>>(emptyList())
    val parcelsData: StateFlow<List<ParcelData>> = _parcelsData.asStateFlow()

    // 时间过滤器
    private val _timeFilterIndex = MutableStateFlow(0)
    val timeFilterIndex: StateFlow<Int> = _timeFilterIndex.asStateFlow()

    fun setTimeFilterIndex(i: Int) {
        _timeFilterIndex.value = i
    }

    fun setAllCompletedIds(list: List<String>) {
        _allCompletedIds.value = list
    }

    fun addCompletedIds(list: List<String>) {
        val data = _allCompletedIds.value.toMutableList()
        data.addAll(list)
        _allCompletedIds.value = data
        _parcelsData.value = SmsProcessor.recalculateParcels(_parcelsData.value, _allCompletedIds.value)
        // 🔴 successSmsData 也要同步打标记：地图取件页 / 路线页 / 条码试验页都读它并按
        //    `!it.isCompleted` 过滤 —— 之前只刷 parcelsData，另几处看到的是旧状态
        //    （点了「已取件」不消失 / 已取件的又冒出来，用户 2026-10-01 反馈）。
        _successSmsData.value = withCompletedMarks(_successSmsData.value, _allCompletedIds.value)
    }

    fun removeCompletedId(key: String) {
        val data = _allCompletedIds.value.toMutableList()
        data.remove(key)
        _allCompletedIds.value = data
        _parcelsData.value = SmsProcessor.recalculateParcels(_parcelsData.value, _allCompletedIds.value)
        _successSmsData.value = withCompletedMarks(_successSmsData.value, _allCompletedIds.value)
    }

    fun clearData() {
        _successSmsData.value = emptyList()
        _failedMessages.value = emptyList()
        _parcelsData.value = emptyList()
    }

    fun getAllMessage(list: List<SmsModel>) {
        _allMessages.value = list
        handleReceivedSms()
    }
    
    fun getAllMessageWithCustom(list: List<SmsModel>, customSmsList: List<SmsModel>) {
        val combinedList = list + customSmsList
        _allMessages.value = combinedList
        handleReceivedSms()
    }

    // 处理接收到的短信
    fun handleReceivedSms() {
        clearData()
        viewModelScope.launch {
            val allMessages = _allMessages.value
            val completedIds = _allCompletedIds.value
            val addressMappings = context?.let { getAddressMappings(it) } ?: emptyMap()

            val result = withContext(Dispatchers.Default) {
                SmsProcessor.process(allMessages, smsParser, completedIds, addressMappings)
            }

            // 🔴 successSmsData 必须带上 isCompleted：`result.successful` 是分组前的原始列表，
            //    **从不打完成标记** ⇒ 刚进 App / 换时间过滤后，地图取件页读它按 `!it.isCompleted`
            //    过滤时**会把已取件的也显示出来**（用户 2026-10-01 反馈）。
            //    保持列表原始语义（顺序/条数/去重都不动），只重算标记。
            _successSmsData.value = withCompletedMarks(result.successful, completedIds)
            _failedMessages.value = result.failed
            _parcelsData.value = result.parcels
        }
    }

    // 将自定义规则添加到 SmsParser
    fun addCustomAddressPattern(pattern: String) {
        smsParser.addCustomAddressPattern(pattern)
    }

    fun addCustomCodePattern(pattern: String) {
        smsParser.addCustomCodePattern(pattern)
    }

    fun addCustomCodeKeyword(keyword: String) {
        smsParser.addCustomCodeKeyword(keyword)
    }

    fun addIgnoreKeyword(keyword: String) {
        smsParser.addIgnoreKeyword(keyword)
    }

    fun clearAllCustomPatterns() {
        smsParser.clearAllCustomPatterns()
    }

    fun setPreferLockerAddress(enabled: Boolean) {
        smsParser.preferLockerAddress = enabled
    }

}

/**
 * 给成功解析的短信列表**重算完成标记**（纯函数，可 JVM 单测）。
 *
 * 规则与 `SmsProcessor.recalculateParcels` 完全一致：键 = `${sms.id}_${sms.timestamp}` 或 `sms.id`。
 * 不改列表的顺序与条数 —— 只是把 `isCompleted` 标对。
 *
 * 为什么需要它：`SmsProcessor.process` 返回的 `successful` 是分组前的原始列表，
 * 从不打 isCompleted；而地图取件页 / 路线页 / 条码试验页都读 `successSmsData`
 * 并按 `!it.isCompleted` 过滤 —— 不打标记就会把已取件的也当作待取（用户 2026-10-01 反馈）。
 */
private fun withCompletedMarks(list: List<SmsData>, completedIds: List<String>): List<SmsData> {
    if (completedIds.isEmpty()) {
        return if (list.any { it.isCompleted }) list.map { it.copy(isCompleted = false) } else list
    }
    val done = completedIds.toHashSet()
    return list.map { d ->
        val completed = done.contains(d.id) || done.contains(d.sms.id)
        if (d.isCompleted == completed) d else d.copy(isCompleted = completed)
    }
}


