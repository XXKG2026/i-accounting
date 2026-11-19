package com.qinkun.accountbook.data

/**
 * CSV 编解码。
 *
 * 只服务我们自己的 8 列格式，不追求完整 RFC 4180 —— 够用且能单测就够了。
 * 抽成独立对象是为了脱离 Android 依赖做纯 JVM 测试：导入导出最怕的是
 * 「商户名里有逗号 → 整行错位」这种静默数据损坏。
 */
internal object CsvCodec {

    /** 字段转义：含逗号或引号时用双引号包起来，内部引号翻倍；换行压成空格 */
    fun escape(s: String): String {
        val v = s.replace('\r', ' ').replace('\n', ' ')
        return if (v.any { it == ',' || it == '"' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
    }

    /** 单行解析，支持双引号包裹与内部转义引号 */
    fun parseLine(line: String): List<String> {
        val out = ArrayList<String>(8)
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '"' && line.getOrNull(i + 1) == '"' -> {
                    sb.append('"'); i++
                }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> {
                    out += sb.toString(); sb.clear()
                }
                else -> sb.append(c)
            }
            i++
        }
        out += sb.toString()
        return out
    }
}
