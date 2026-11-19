package com.qinkun.accountbook

import com.qinkun.accountbook.data.CsvCodec
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * CSV 编解码测试。
 *
 * 导入导出最怕的不是报错，而是**静默错位**：商户名里一个逗号，
 * 后面所有列就串位，恢复完的数据全歪了还不报错。
 */
class CsvCodecTest {

    @Test
    fun plain_values_pass_through() {
        assertEquals("天外山", CsvCodec.escape("天外山"))
        assertEquals(listOf("a", "b", "c"), CsvCodec.parseLine("a,b,c"))
    }

    @Test
    fun comma_forces_quoting() {
        val raw = "星巴克,高新店"
        assertEquals("\"星巴克,高新店\"", CsvCodec.escape(raw))
        assertEquals(listOf("星巴克,高新店", "32.00"), CsvCodec.parseLine("\"星巴克,高新店\",32.00"))
    }

    @Test
    fun inner_quote_is_doubled() {
        val raw = "他说\"好\""
        assertEquals("\"他说\"\"好\"\"\"", CsvCodec.escape(raw))
        assertEquals(listOf("他说\"好\""), CsvCodec.parseLine(CsvCodec.escape(raw)))
    }

    @Test
    fun newline_is_flattened() {
        // 原文里带换行会把一行 CSV 撑成两行，整张表就错位了
        assertEquals("第一行 第二行", CsvCodec.escape("第一行\n第二行"))
        assertEquals("第一行 第二行", CsvCodec.escape("第一行\r第二行"))
    }

    @Test
    fun empty_fields_are_kept() {
        assertEquals(listOf("", "", ""), CsvCodec.parseLine(",,"))
        assertEquals(listOf("a", "", "c"), CsvCodec.parseLine("a,,c"))
    }

    @Test
    fun full_row_roundtrip() {
        // 真实流水里最刁的一行：商户带逗号、原文带引号和逗号
        val cols = listOf(
            "2026-11-19 12:47:32", "198.00", "收入", "经营",
            "老王,水果店", "支付宝", "", "你已成功收款\"198.00\"元, 已转入余额",
        )
        val line = cols.joinToString(",") { CsvCodec.escape(it) }
        assertEquals(cols, CsvCodec.parseLine(line))
    }

    @Test
    fun last_field_with_trailing_quote_char() {
        // 字段以引号结尾时不能被误吞
        val cols = listOf("a", "b\"")
        assertEquals(cols, CsvCodec.parseLine(cols.joinToString(",") { CsvCodec.escape(it) }))
    }
}
