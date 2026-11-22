package com.qinkun.accountbook.util

/**
 * 把用户输入的金额文本转成「元」。容忍千分位逗号、货币符号、全角数字和空格；
 * 解析不了返回 null。
 */
fun parseYuanInput(input: String): Double? {
    val sb = StringBuilder()
    for (ch in input) {
        when {
            ch in '０'..'９' -> sb.append('0' + (ch - '０'))
            ch == '．' || ch == '。' -> sb.append('.')
            ch.isDigit() || ch == '.' -> sb.append(ch)
            ch == ',' || ch == '，' || ch == '¥' || ch == '￥' || ch.isWhitespace() -> Unit
            else -> return null
        }
    }
    val d = sb.toString().toDoubleOrNull() ?: return null
    if (d.isNaN() || d.isInfinite()) return null
    return d
}
