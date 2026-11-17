package com.qinkun.accountbook.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一条通知的处理记录。
 *
 * 为什么需要：以前漏记是黑盒 —— 不知道是"系统没给通知"还是"给了但没认出"。
 * 有了这张表，打开「抓取记录」就能看到每一条通知的原文和处理结果。
 *
 * 只保留最近几天，自动清理，不占空间。文本入库前已脱敏。
 */
@Entity(tableName = "capture_logs", indices = [Index("at")])
data class CaptureLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 通知时间 */
    val at: Long,
    /** 来源 App 显示名 */
    val sourceApp: String,
    /** 通知标题 */
    val title: String,
    /** 通知正文（已脱敏，已截断） */
    val text: String,
    /** 是否成功记入账本 */
    val success: Boolean,
    /** 结果说明：成功=「支出 ¥25.80 · 餐饮」；失败=原因 */
    val reason: String,
    /** 成功时的金额（分），失败为 0 */
    val amountCents: Long = 0,
)
