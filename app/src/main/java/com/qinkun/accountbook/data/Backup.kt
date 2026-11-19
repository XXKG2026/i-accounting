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
                    ).joinToString(",") { CsvCodec.escape(it) }
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
            val cols = CsvCodec.parseLine(raw)
            if (cols.size < 5) { failed++; continue }

            val at = runCatching { FMT.parse(cols[0].trim())?.time }.getOrNull()
            val cents = parseYuanToCents(cols[1])
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

    // ==================================================================
    //  Excel (.xlsx) 导入
    // ==================================================================

    /** 表头别名：各平台导出的列名不一样，按「包含」匹配，越长越优先 */
    private val HEAD_ALIAS: List<Pair<String, List<String>>> = listOf(
        "date" to listOf("账单日期", "交易时间", "交易创建时间", "日期", "时间", "date", "time"),
        "amount" to listOf("交易金额", "金额(元)", "金额", "amount"),
        "io" to listOf("收支类型", "收/支", "收支", "类型", "方向"),
        "cat" to listOf("记账分类", "交易分类", "分类筛选", "分类", "category"),
        "merchant" to listOf("交易对方", "商品说明", "备注", "商户", "商家", "对方", "memo"),
    )

    private fun findCol(header: List<String>, key: String): Int {
        val aliases = HEAD_ALIAS.first { it.first == key }.second
        // 长别名优先：先找「账单日期」再找「日期」，避免匹配到「日期筛选」这种列
        for (a in aliases.sortedByDescending { it.length }) {
            val i = header.indexOfFirst { it.contains(a, ignoreCase = true) }
            if (i >= 0) return i
        }
        return -1
    }

    /**
     * 把「元」字符串转成「分」。
     *
     * 必须四舍五入，不能截断：19.99 的 double 是 19.989999999999998，
     * 乘 100 得 1998.9999999999998，`toLong()` 会截成 1998 —— 导出再导入
     * 就少 1 分钱，而且毫无提示。CSV 和 Excel 两条路径都走这里。
     */
    internal fun parseYuanToCents(raw: String): Long? =
        runCatching { Math.round(raw.trim().replace(",", "").toDouble() * 100) }.getOrNull()

    /** Excel 把日期存成「从 1899-12-30 起的天数」，小数部分是一天中的时刻 */
    internal fun excelSerialToMillis(v: Double): Long {
        val days = v.toLong()
        val frac = v - days
        val cal = java.util.GregorianCalendar().apply {
            clear()
            set(1899, java.util.Calendar.DECEMBER, 30)
            add(java.util.Calendar.DAY_OF_YEAR, days.toInt())
        }
        return cal.timeInMillis + Math.round(frac * 86_400_000.0)
    }

    private val DATE_PATTERNS = listOf(
        "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd",
        "yyyy/MM/dd HH:mm:ss", "yyyy/MM/dd HH:mm", "yyyy/MM/dd",
        "yyyy年M月d日 HH:mm:ss", "yyyy年M月d日", "yyyyMMddHHmmss",
    )

    /** 数字当 Excel 序列号，文本按常见格式逐个试 */
    private fun parseLooseDate(raw: String): Long? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        t.toDoubleOrNull()?.let { v ->
            // 1982-01-01 ~ 2118-12-31 之间才当序列号，避免把「20250701」这种误判
            if (v > 30_000 && v < 80_000) return excelSerialToMillis(v)
        }
        for (pat in DATE_PATTERNS) {
            runCatching {
                val f = SimpleDateFormat(pat, Locale.CHINA).apply { isLenient = false }
                f.parse(t)?.let { return it.time }
            }
        }
        return null
    }

    /**
     * 把外部账单的分类映射到本 App 的分类。
     *
     * 原则（主人定的）：能认出具体类型就归具体类型，认不出就归「其他」，不瞎猜。
     * 特别约定：「旅行」归「经营」—— 那部分基本是出差和进货路费。
     */
    internal fun mapCategory(raw: String, type: TxType): String {
        val s = raw.trim()
        if (type == TxType.INCOME) {
            return when {
                s.contains("薪") || s.contains("工资") -> "工资"
                s.contains("红包") -> "红包"
                s.contains("理财") || s.contains("基金") || s.contains("利息") -> "理财"
                s.contains("退款") || s.contains("退货") -> "退款"
                s.contains("报销") -> "报销"
                s.contains("转账") -> "转账"
                // 「收款」「其他」在网店账单里都是卖货进账
                s.isEmpty() || s == "其他" || s.contains("收款") || s.contains("经营") -> "经营"
                else -> "经营"
            }
        }
        return when {
            s.contains("餐") || s.contains("食材") || s.contains("咖啡") || s.contains("奶茶") ||
                s.contains("水果") || s.contains("零食") || s.contains("外卖") -> "餐饮"
            s.contains("购物") || s.contains("美妆") || s.contains("穿搭") || s.contains("数码") ||
                s.contains("手机") || s.contains("电器") -> "购物"
            s.contains("交通") || s.contains("地铁") || s.contains("公交") || s.contains("加油") ||
                s.contains("充电") || s.contains("高速") || s.contains("停车") -> "交通"
            s.contains("日用") || s.contains("租电") || s.contains("水电") -> "日用"
            s.contains("娱乐") || s.contains("电影") || s.contains("门票") || s.contains("游戏") -> "娱乐"
            s.contains("快递") || s.contains("物流") || s.contains("跑腿") -> "跑腿快递"
            s.contains("住房") || s.contains("房租") || s.contains("房贷") || s.contains("还贷") -> "住房"
            s.contains("医疗") || s.contains("药") || s.contains("医院") -> "医疗"
            s.contains("通讯") || s.contains("话费") || s.contains("流量") -> "通讯"
            s.contains("教育") || s.contains("学费") || s.contains("培训") -> "教育"
            s.contains("宠物") -> "宠物"
            s.contains("红包") || s.contains("人情") -> "人情"
            // 主人指定：旅行算经营支出（出差、进货路费）
            s.contains("旅行") || s.contains("差旅") || s.contains("出差") -> "经营"
            s.contains("进货") || s.contains("货款") || s.contains("办公") -> "经营"
            s.contains("转账") -> "转账"
            // 「消费」「其他」或空：认不出具体买什么
            else -> "其他"
        }
    }

    /**
     * 导入 Excel 账单（.xlsx）。
     *
     * 表头自动识别，所以支付宝、微信、自己用表格记的账都能吃。
     * 去重键和 CSV 导入一致：秒级时间 + 金额 + 商户。
     */
    suspend fun importXlsx(ctx: Context, uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        // 先落到缓存目录再解析：ZipInputStream 读不了用 data descriptor 写的文件
        // （真机上直接报 invalid entry size），ZipFile 得能 seek 才行。
        val tmp = java.io.File.createTempFile("import_", ".xlsx", ctx.cacheDir)
        val rows = try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { out -> input.copyTo(out) }
            } ?: return@withContext ImportResult(message = "打不开文件")
            XlsxReader.readRows(tmp)
        } catch (e: Exception) {
            Log.e(TAG, "解析 xlsx 失败", e)
            return@withContext ImportResult(message = "这个 Excel 读不了：" + (e.localizedMessage ?: e.javaClass.simpleName))
        } finally {
            tmp.delete()
        }
        if (rows.isEmpty()) return@withContext ImportResult(message = "表格是空的")

        // 表头可能不在第一行（支付宝导出的前面常有说明行）
        val headIdx = rows.take(20).indexOfFirst { r ->
            val j = r.joinToString(" ")
            (j.contains("日期") || j.contains("时间")) && (j.contains("金额") || j.contains("amount", true))
        }
        if (headIdx < 0) {
            return@withContext ImportResult(message = "找不到表头：需要一列含「日期/时间」，一列含「金额」")
        }
        val header = rows[headIdx]
        val cDate = findCol(header, "date")
        val cAmt = findCol(header, "amount")
        val cIo = findCol(header, "io")
        val cCat = findCol(header, "cat")
        val cMer = findCol(header, "merchant")
        if (cDate < 0 || cAmt < 0) {
            return@withContext ImportResult(message = "表头里没找到「日期」或「金额」列")
        }

        val repo = Repo(ctx)
        val existing = repo.allOnce()
            .map { Triple(it.occurredAt / 1000, it.amountCents, it.merchant) }
            .toHashSet()

        val batch = ArrayList<Transaction>()
        var skipped = 0
        var failed = 0

        for (r in rows.drop(headIdx + 1)) {
            if (r.all { it.isBlank() }) continue
            fun cell(i: Int) = if (i >= 0 && i < r.size) r[i].trim() else ""

            val at = parseLooseDate(cell(cDate))
            val cents = parseYuanToCents(cell(cAmt))
            if (at == null || cents == null) { failed++; continue }
            // 负数在支付宝账单里表示退款，方向看「收支」列；这里只取绝对值
            val abs = kotlin.math.abs(cents)
            if (abs == 0L) { failed++; continue }

            // 方向：优先看「收支」列；没有这一列（或为空）时一律按支出算 ——
            // 记账 App 里九成是消费，宁可让用户手动改一两笔收入，也不用金额正负去猜
            // （退款在支付宝账单里是负数，把它当收入会让月度统计虚高）。
            val ioRaw = cell(cIo)
            val type = if (ioRaw.contains("收")) TxType.INCOME else TxType.EXPENSE
            // 入库用的是 ifBlank 之后的商户名，去重键必须用同一个值，
            // 否则「商户为空」的行第一次存成「导入」，第二次查重拿 "" 去比，
            // 永远对不上 —— 同一个文件导两遍就翻倍。
            val merchant = cell(cMer).ifBlank { "导入" }
            val category = mapCategory(cell(cCat), type)

            val key = Triple(at / 1000, abs, merchant)
            if (key in existing) { skipped++; continue }
            existing.add(key)

            batch.add(
                Transaction(
                    amountCents = abs,
                    type = type,
                    category = category,
                    merchant = merchant,
                    sourceApp = "Excel导入",
                    note = if (ioRaw.isNotBlank()) ioRaw else "",
                    occurredAt = at,
                    rawText = r.joinToString(" | ") { it.trim() },
                )
            )
        }

        if (batch.isEmpty()) {
            return@withContext ImportResult(
                added = 0, skipped = skipped, failed = failed,
                message = "没有新记录（跳过重复 $skipped 条，格式错误 $failed 条）",
            )
        }

        val added = runCatching { repo.addAll(batch); batch.size }
            .getOrElse { e ->
                // 写库失败不是「格式错误」，不能混进那个计数里骗人
                Log.e(TAG, "批量导入失败", e)
                return@withContext ImportResult(
                    added = 0, skipped = skipped, failed = 0,
                    message = "写入数据库失败：" + (e.localizedMessage ?: e.javaClass.simpleName),
                )
            }

        ImportResult(
            added = added, skipped = skipped, failed = failed,
            message = "导入 $added 条，跳过重复 $skipped 条" + if (failed > 0) "，格式错误 $failed 条" else "",
        )
    }
}
