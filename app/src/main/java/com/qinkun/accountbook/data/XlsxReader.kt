package com.qinkun.accountbook.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.zip.ZipFile

/**
 * 极简 xlsx 读取器：只做「把第一个工作表读成一行行文本」这一件事。
 *
 * 为什么不用 Apache POI：它十几 MB，还要拖一堆传递依赖，而这个 App 只有 54 MB
 * 是给界面和图标用的。xlsx 本质就是 zip 包里的几个 XML，安卓自带的
 * `ZipFile` + `XmlPullParser` 足够，一个字节都不多占。
 *
 * 为什么收 File 而不是 InputStream（踩过的坑）：
 *   一开始用 `ZipInputStream` 直接读 SAF 给的流，真机上报
 *   `invalid entry size (expected 0 but got 1053 bytes)` ——
 *   很多文件在写 zip 时把大小放在「data descriptor」里，local header 的 size 是 0，
 *   流式读取器按 0 去校验就炸。`ZipFile` 读的是中央目录，大小是准的。
 *   所以调用方必须先落盘（xlsx 一般才几百 KB，落盘无所谓）。
 *
 * 已知取舍：
 *   · 只读第一个工作表（支付宝/微信/手写的账单一律只有一张表）
 *   · 不解析公式，只取缓存值（导出文件里 `t="str"` 就是算好的结果）
 *   · 不处理合并单元格 —— 账单不会有
 */
internal object XlsxReader {

    /**
     * 读出所有行，每行是一组单元格文本；缺失的列补空串。
     *
     * 之所以按「行宽」补齐而不是保留稀疏结构：调用方要按列号取字段，
     * 一旦某行少几列就会错位，宁可补空串让 `isBlank()` 去判断。
     */
    fun readRows(file: File): List<List<String>> {
        ZipFile(file).use { zip ->
            val names = zip.entries().toList().map { it.name }
            val sheetKey = names
                .filter { it.startsWith("xl/worksheets/sheet") }
                .minOrNull() ?: return emptyList()

            val shared = zip.getEntry("xl/sharedStrings.xml")
                ?.let { e -> zip.getInputStream(e).use { parseSharedStrings(it.readBytes()) } }
                ?: emptyList()

            val sheetXml = zip.getEntry(sheetKey)?.let { e ->
                zip.getInputStream(e).use { it.readBytes() }
            } ?: return emptyList()

            return parseSheet(sheetXml, shared)
        }
    }

    /** `xl/sharedStrings.xml`：所有字符串集中存在这儿，单元格里只放索引 */
    private fun parseSharedStrings(xml: ByteArray): List<String> {
        val out = ArrayList<String>()
        val p = Xml.newPullParser()
        p.setInput(xml.inputStream(), null)

        val sb = StringBuilder()
        var inSi = false
        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "si" -> { sb.setLength(0); inSi = true }
                    // 富文本会拆成多个 <r><t>，逐个拼起来
                    "t" -> if (inSi) sb.append(p.nextText())
                }
                XmlPullParser.END_TAG -> if (p.name == "si") {
                    out += sb.toString()
                    inSi = false
                }
            }
            event = p.next()
        }
        return out
    }

    private fun parseSheet(xml: ByteArray, shared: List<String>): List<List<String>> {
        val rows = ArrayList<List<String>>()
        val p = Xml.newPullParser()
        p.setInput(xml.inputStream(), null)

        var cur: ArrayList<String>? = null
        var col = -1
        var type: String? = null
        var text = StringBuilder()
        var inCell = false

        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "row" -> cur = ArrayList()
                    "c" -> {
                        col = colOf(p.getAttributeValue(null, "r"))
                        type = p.getAttributeValue(null, "t")
                        text = StringBuilder()
                        inCell = true
                    }
                    // <v> 数字或共享字符串索引；<t> 内联字符串（inlineStr）
                    "v", "t" -> if (inCell) text.append(p.nextText())
                }
                XmlPullParser.END_TAG -> when (p.name) {
                    "c" -> {
                        val row = cur
                        if (row != null && col >= 0) {
                            val raw = text.toString()
                            val v = if (type == "s") {
                                shared.getOrNull(raw.toIntOrNull() ?: -1) ?: ""
                            } else raw
                            while (row.size <= col) row.add("")
                            row[col] = v
                        }
                        inCell = false
                    }
                    "row" -> {
                        cur?.let { rows.add(it) }
                        cur = null
                    }
                }
            }
            event = p.next()
        }
        return rows
    }

    /** "A1" → 0，"B7" → 1，"AA3" → 26 */
    private fun colOf(ref: String?): Int {
        if (ref.isNullOrEmpty()) return -1
        var c = 0
        for (ch in ref) {
            if (ch in 'A'..'Z') c = c * 26 + (ch - 'A' + 1) else break
        }
        return c - 1
    }
}
