package com.qinkun.accountbook

import com.qinkun.accountbook.service.ScreenPageParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 美团外卖付款页的三类错位（真实线上出错，节点取自手机抓取记录）：
 *
 * 1. 把「商家正在备餐，骑手到店取货中」这种配送状态当成商户名记进账本
 * 2. 把推荐卡片上的价格（¥15.96 3.9折）当成实付金额，记成一笔 15.00 支出
 * 3. 商户名抓成「您已达标(41/41)次」这种营销文案，而不是真实店名
 *
 * 期望：能拿到实付金额就用「支付方式」旁边的那个数；拿不到就不记（宁可漏记，不许记错）。
 */
class V43Test {

    private val MT = "com.sankuai.meituan"
    private val MTT = "com.sankuai.meituan.takeoutnew"

    private fun page(vararg nodes: String) = nodes.toList()

    /** 真实页面：只有配送状态 + 推荐卡，页面上没有实付金额（金额被渲染成图片了） */
    private val deliveryOnlyPage = page(
        "支付成功", "预计017:47-18:020送达", "商家正在备餐，骑手到店取货中", "订单详情", "1",
        "成长值+27", "收货后到账", "查看权益", "美团外卖", "外卖福利派送中", "外卖", "32分钟",
        "重庆城口老腊肉排骨饭", "双流区川渝火锅热销榜第 5 名", "66 减 2", "104 减 4", "150 减 6",
        "团购", "<500m", "云酱酱·云南小锅米线 【傣味酸汤】金汤百香果小锅米", "本店热销第 2 名",
        "回头客 480", "¥", "15", ".", "96", "3.9 折", "半年售 2 千+", "小象超市", "最快30分钟",
        "老街口山核桃味瓜子 500g", "手工炒制｜层层筛选", "12", "闪购", "15分钟", "农夫山泉 饮用天然水",
        "3", "80", "第 1 件价", "月售 100+", "首页",
    )

    @Test
    fun deliveryStatusPage_isRejected_notRecordedAsAmount() {
        val o = ScreenPageParser.analyze(MTT, deliveryOnlyPage)
        assertTrue("推荐卡上的 ¥15.96 不能被记成实付金额", o is ScreenPageParser.Outcome.Rejected)
    }

    /** 真实页面：支付成功 / ¥26.90 / 支付方式 / 招商银行信用卡(7161) + 达标营销文案 */
    private val paidByCreditCard = page(
        "¥", "10", "满20减", "10元专属福利！送酒通用", "支付成功", "¥26.90", "支付方式",
        "招商银行信用卡(7161)", "本单使用【信用卡】支付成功", "您已达标(41/41)次", "1次奶茶免单",
        "屏蔽", "今日下单奖励", "10 元", "第 40 次消费奖励", "1 张 神券", "可兑换",
        "消费已达标(40/40)单", "完成",
    )

    @Test
    fun creditCardPage_recordsAmountAndPayMethod() {
        val o = ScreenPageParser.analyze(MTT, paidByCreditCard)
        assertTrue("这页应该记账", o is ScreenPageParser.Outcome.Ok)
        val p = (o as ScreenPageParser.Outcome.Ok).parsed
        assertEquals(2690L, p.amountCents)
        assertTrue("备注里要有信用卡付款信息", p.note.contains("招商银行信用卡(7161)"))
        assertTrue("这是付款回执，不是下单页", p.note.contains("付款"))
        assertFalse("营销文案不能当商户名", p.merchant.contains("您已达标"))
    }

    /** 真实页面：店名在「支付成功」上面 */
    private val namedStorePage = page(
        "兵哥豌豆面（海昌路店）", "[邀您加入粉丝群]", "回复", "支付成功", "¥18.80",
        "支付方式", "招商银行信用卡(7161)", "本单使用【信用卡】支付成功", "您已达标(38/38)次", "15 元",
    )

    @Test
    fun storeNameAboveAnchor_isPicked() {
        val o = ScreenPageParser.analyze(MT, namedStorePage)
        assertTrue(o is ScreenPageParser.Outcome.Ok)
        val p = (o as ScreenPageParser.Outcome.Ok).parsed
        assertEquals("兵哥豌豆面（海昌路店）", p.merchant)
        assertEquals(1880L, p.amountCents)
    }

    @Test
    fun anotherStoreName_isPicked() {
        val o = ScreenPageParser.analyze(
            MT,
            page(
                "满庭居·江湖菜", "[邀您加入粉丝群]", "回复", "支付成功", "¥19.90", "支付方式",
                "招商银行信用卡(7161)", "本单使用【信用卡】支付成功", "您已达标(36/36)次", "15 元",
            ),
        )
        assertTrue(o is ScreenPageParser.Outcome.Ok)
        assertEquals("满庭居·江湖菜", (o as ScreenPageParser.Outcome.Ok).parsed.merchant)
    }

    @Test
    fun deliveryKeywordNeverBecomesMerchant() {
        val merchant = ScreenPageParser.extractMerchant(
            page("支付成功", "商家正在备餐，骑手到店取货中", "骑手已接单", "订单详情"),
            0,
            preferBackward = true,
        )
        assertTrue("配送状态行不能当商户名：$merchant", merchant == null || !merchant.contains("骑手"))
    }

    @Test
    fun orderProgressNotification_isNotRecorded() {
        // 骑手/配送日程类通知：本来就不是付款
        val parsed = com.qinkun.accountbook.service.TransactionParser.parse(
            "com.sankuai.meituan.takeoutnew",
            "骑手已接单",
            listOf("骑手正赶往商家，请您耐心等待。为保护您的权益，平台可能会在骑手送达时通过骑手接单App进行录音。", "骑手已接单"),
        )
        assertTrue("骑手接单通知不该记账", parsed == null || parsed.type != com.qinkun.accountbook.data.TxType.EXPENSE)
    }

    @Test
    fun paymentNotification_stillWorks() {
        val parsed = com.qinkun.accountbook.service.TransactionParser.parse(
            "com.sankuai.meituan", "您已成功付款26.90元",
            listOf("您的美团订单已支付成功，点击查看详情>", "您已成功付款26.90元"),
        )
        assertNotNull("付款通知必须能记上", parsed)
        assertEquals(2690L, parsed!!.amountCents)
    }
}
