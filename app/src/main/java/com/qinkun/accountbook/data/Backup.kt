package com.qinkun.accountbook.data

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 流水导入导出（CSV）。
 *
 * 为什么用 CSV：Excel / Numbers / 手机自带表格都能直接打开，
 * 也不用引入 JSON 库。字段顺序固定，第一行是表头。
 *
 * 走 SAF（系统文件选择器），不需要任何存储权限 —— 这也是 App
 * 能一直不申请 INTERNET / 存储权限的原因。
 */
object Backup {

    private const val TAG = "Backup"

    /** CSV 列顺序，导入导出都以它为准 */
    private val HEADER = listOf(
        "时间", "金额", "类型", "分类", "商户", "来源App", "备注", "原文",
    )

    private val FMT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

    data class ImportResult(
        val added: Int = 0,
        val skipped: Int = 0,
        val failed: Int = 0,
        val message: String = "",
    )

    /** 导出全部流水，返回导出条数；失败抛异常由调用方捕获 */
    suspend fun exportCsv(ctx: Context, uri: Uri): Int = withContext(Dispatchers.IO) {
        val rows = Repo(ctx).allOnce()
        ctx.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { w ->
            w.write(HEADER.joinToString(","))
            w.newLine()
            for (t in rows) {
                w.write(
                    listOf(
                        FMT.format(Date(t.occurredAt)),
                        String.format(Locale.US, "%.2f", t.amountCents / 100.0),
                        if (t.type == TxType.EXPENSE) "支出" else "收入",
                        t.category,
                        t.merchant,
                        t.sourceApp,
                        t.note,
                        t.rawText,
                    ).joinToString(",") { esc(it) }
                )
                w.newLine()
            }
        } ?: error("无法写入文件")
        Log.i(TAG, "导出 ${rows.size} 条")
        rows.size
    }

    /**
     * 导入 CSV。
     *
     * 去重规则：同一秒 + 同金额 + 同商户 视为已存在 —— 反复导入同一个文件
     * 不会产生重复流水。这也让"导出→改分类→再导入"能当备份还原用。
     */
    suspend fun importCsv(ctx: Context, uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val repo = Repo(ctx)
        var added = 0
        var skipped = 0
        var failed = 0

        val lines = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readLines() }
            ?: return@withContext ImportResult(message = "打不开文件")

        if (lines.isEmpty()) return@withContext ImportResult(message = "文件是空的")

        // 第一行若是表头就跳过
        val start = if (lines.first().contains("金额") || lines.first().contains("时间")) 1 else 0

        val existing = repo.allOnce()
            .map { Triple(it.occurredAt / 1000, it.amountCents, it.merchant) }
            .toHashSet()

        for (raw in lines.drop(start)) {
            if (raw.isBlank()) continue
            val cols = parseCsvLine(raw)
            if (cols.size < 5) { failed++; continue }

            val at = runCatching { FMT.parse(cols[0].trim())?.time }.getOrNull()
            val cents = runCatching {
                (cols[1].trim().toDouble() * 100).toLong()
            }.getOrNull()
            if (at == null || cents == null || cents <= 0) { failed++; continue }

            val type = if (cols[2].contains("收")) TxType.INCOME else TxType.EXPENSE
            val category = cols[3]
            val merchant = cols[4]
            val key = Triple(at / 1000, cents, merchant)
            if (key in existing) { skipped++; continue }

            runCatching {
                repo.add(
                    Transaction(
                        amountCents = cents,
                        type = type,
                        category = category,
                        merchant = merchant,
                        sourceApp = cols.getOrElse(5) { "导入" }.ifBlank { "导入" },
                        note = cols.getOrElse(6) { "" },
                        occurredAt = at,
                        rawText = cols.getOrElse(7) { "" },
                    )
                )
                existing.add(key)
                added++
            }.onFailure { failed++ }
        }

        ImportResult(
            added = added, skipped = skipped, failed = failed,
            message = "新增 $added 条，跳过重复 $skipped 条" + if (failed > 0) "，格式错误 $failed 条" else "",
        )
    }

    /** CSV 字段转义：含逗号/引号/换行时用双引号包起来，内部引号翻倍 */
    private fun esc(s: String): String {
        val v = s.replace("\r", " ").replace("\n", " ")
        return if (v.contains(',') || v.contains('"')) "\"" + v.replace("\"", "\"\"") + "\"" else v
    }

    /** 简易 CSV 行解析，支持双引号包裹 */
    private fun parseCsvLine(line: String): List<String> {
        val out = ArrayList<String>(8)
        val sb = StringBuilder()
        var inQuote = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQuote && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    sb.append('"'); i++
                }
                c == '"' -> inQuote = !inQuote
                c == ',' && !inQuote -> { out.add(sb.toString()); sb.clear() }
                else -> sb.append(c)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }
}
