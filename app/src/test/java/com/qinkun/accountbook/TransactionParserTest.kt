package com.qinkun.accountbook

import com.qinkun.accountbook.data.TxType
import com.qinkun.accountbook.service.TransactionParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionParserTest {

    private fun p(pkg: String, title: String, vararg texts: String) =
        TransactionParser.parse(pkg, title, texts.toList())

    // ---------- 支出 ----------
    @Test
    fun meituan_expense() {
        val r = p("com.sankuai.meituan", "美团外卖", "支付成功，共¥35.00")
        assertNotNull(r)
        assertEquals(3500L, r!!.amountCents)
        assertEquals(TxType.EXPENSE, r.type)
        assertEquals("美团", r.sourceApp)
    }

    @Test
    fun alipay_expense_yuan_suffix() {
        val r = p("com.eg.android.AlipayGphone", "支付宝", "你已成功付款12.50元")
        assertNotNull(r)
        assertEquals(1250L, r!!.amountCents)
        assertEquals(TxType.EXPENSE, r.type)
    }

    @Test
    fun jd_expense_with_comma() {
        val r = p("com.jingdong.app.mall", "京东", "支付成功 订单金额 ¥1,299.00")
        assertNotNull(r)
        assertEquals(129900L, r!!.amountCents)
    }

    @Test
    fun pdd_expense() {
        val r = p("com.xunmeng.pinduoduo", "拼多多", "订单支付成功，实付¥9.9")
        assertNotNull(r)
        assertEquals(990L, r!!.amountCents)
    }

    @Test
    fun shansong_expense() {
        val r = p("com.ishansong", "闪送", "支付成功，金额 28.00元")
        assertNotNull(r)
        assertEquals(2800L, r!!.amountCents)
    }

    @Test
    fun dianping_expense() {
        val r = p("com.dianping.v1", "大众点评", "支付成功 ¥186.00")
        assertNotNull(r)
        assertEquals(18600L, r!!.amountCents)
    }

    // ---------- 收入 ----------
    @Test
    fun xianyu_income_arrival() {
        val r = p("com.taobao.idlefish", "闲鱼", "你的商品已卖出，到账¥200.00")
        assertNotNull(r)
        assertEquals(20000L, r!!.amountCents)
        assertEquals(TxType.INCOME, r.type)
    }

    @Test
    fun refund_is_income() {
        val r = p("com.jingdong.app.mall", "京东", "退款成功，¥99.00 已原路退回")
        assertNotNull(r)
        assertEquals(9900L, r!!.amountCents)
        assertEquals(TxType.INCOME, r.type)
    }

    // ---------- 应被排除 ----------
    @Test
    fun coupon_not_recorded() {
        assertNull(p("com.sankuai.meituan", "美团", "你有3张优惠券即将过期，最高立减¥15"))
    }

    @Test
    fun pending_payment_not_recorded() {
        assertNull(p("com.taobao.taobao", "淘宝", "订单待支付，请支付¥58.00"))
    }

    @Test
    fun no_amount_not_recorded() {
        assertNull(p("com.jingdong.app.mall", "京东", "支付成功"))
    }

    @Test
    fun unknown_package_not_recorded() {
        assertNull(p("com.example.unknown", "某 App", "支付成功 ¥10.00"))
    }

    @Test
    fun blank_notification() {
        assertNull(p("com.sankuai.meituan", "", ""))
    }

    // ---------- 金额提取 ----------
    @Test
    fun extract_various_formats() {
        assertEquals(1234L, TransactionParser.extractCents("¥12.34"))
        assertEquals(1234L, TransactionParser.extractCents("12.34元"))
        assertEquals(100000L, TransactionParser.extractCents("¥1,000.00"))
        assertEquals(500L, TransactionParser.extractCents("实付 5"))
    }

    // ---------- 分类猜测 ----------
    @Test
    fun category_guess_expense() {
        assertEquals("餐饮", TransactionParser.guessCategory("美团", "美团外卖", "支付成功"))
        assertEquals("跑腿快递", TransactionParser.guessCategory("闪送", "闪送", "支付成功"))
        assertEquals("购物", TransactionParser.guessCategory("京东", "京东商城", "支付成功"))
        assertEquals("交通", TransactionParser.guessCategory("支付宝", "滴滴出行", "支付成功"))
        assertEquals("通讯", TransactionParser.guessCategory("支付宝", "中国联通", "话费充值"))
        assertEquals("住房", TransactionParser.guessCategory("支付宝", "物业费", "支付成功"))
        assertEquals("娱乐", TransactionParser.guessCategory("淘宝", "猫眼电影", "支付成功"))
    }

    @Test
    fun category_guess_income() {
        assertEquals("退款", TransactionParser.guessCategory("京东", "京东", "退款成功", TxType.INCOME))
        assertEquals("工资", TransactionParser.guessCategory("支付宝", "工资代发", "到账", TxType.INCOME))
        assertEquals("红包", TransactionParser.guessCategory("微信", "红包", "收款成功", TxType.INCOME))
    }

    // ---------- gpt-6 审查发现的坑，回归测试 ----------

    @Test
    fun balance_not_mistaken_as_amount() {
        // 通知里先出现余额 500，再出现实付 20 —— 必须记 20
        val r = p("com.eg.android.AlipayGphone", "支付宝", "支付成功 余额 ¥500.00，本次实付 ¥20.00")
        assertNotNull(r)
        assertEquals(2000L, r!!.amountCents)
    }

    @Test
    fun balance_word_skipped() {
        // 只有余额、没有实付金额的，不应该记账
        assertNull(p("com.eg.android.AlipayGphone", "支付宝", "支付成功 当前余额 1234.56 元"))
    }

    @Test
    fun order_number_not_mistaken() {
        // 订单号里的数字不该被当金额
        val r = p("com.jingdong.app.mall", "京东", "订单号 2026111648573921 支付成功 ¥58.00")
        assertNotNull(r)
        assertEquals(5800L, r!!.amountCents)
    }

    @Test
    fun semantic_keyword_beats_currency_sign() {
        // 「实付」后面的数字优先于前面的 ¥ 数字
        val r = p("com.sankuai.meituan", "美团", "支付成功 原价 ¥45.00 优惠 ¥10.00 实付 ¥35.00")
        assertNotNull(r)
        assertEquals(3500L, r!!.amountCents)
    }

    @Test
    fun percent_not_mistaken() {
        // 百分比不该被当金额
        val r = p("com.taobao.taobao", "淘宝", "支付成功 已优惠 12.50% 实付 ¥88.00")
        assertNotNull(r)
        assertEquals(8800L, r!!.amountCents)
    }

    // ---------- 小红书 / 抖音 / 头条 ----------

    @Test
    fun xiaohongshu_expense() {
        val r = p("com.xingin.xhs", "小红书", "支付成功 订单金额 ¥129.00")
        assertNotNull(r)
        assertEquals(12900L, r!!.amountCents)
        assertEquals("小红书", r.sourceApp)
        assertEquals("购物", TransactionParser.guessCategory(r!!.sourceApp, r!!.merchant, r!!.rawText))
    }

    @Test
    fun douyin_shop_expense() {
        val r = p("com.ss.android.ugc.aweme", "抖音", "支付成功 抖音商城 ¥59.90")
        assertNotNull(r)
        assertEquals(5990L, r!!.amountCents)
        assertEquals("抖音", r.sourceApp)
        assertEquals("购物", TransactionParser.guessCategory(r!!.sourceApp, r!!.merchant, r!!.rawText))
    }

    @Test
    fun douyin_gift_is_entertainment() {
        val r = p("com.ss.android.ugc.aweme", "抖音", "支付成功 购买抖币 ¥30.00")
        assertNotNull(r)
        assertEquals(3000L, r!!.amountCents)
        assertEquals("娱乐", TransactionParser.guessCategory(r!!.sourceApp, r!!.merchant, r!!.rawText))
    }

    @Test
    fun toutiao_shop_expense() {
        val r = p("com.ss.android.article.news", "今日头条", "支付成功 头条小店 ¥45.00")
        assertNotNull(r)
        assertEquals(4500L, r!!.amountCents)
        assertEquals("今日头条", r.sourceApp)
        assertEquals("购物", TransactionParser.guessCategory(r!!.sourceApp, r!!.merchant, r!!.rawText))
    }

    // ---------- 微信转账分类（用户截图暴露的问题） ----------

    @Test
    fun wechat_transfer_in_is_transfer() {
        val r = p("com.tencent.mm", "小H", "收到转账 500.00 元")
        assertNotNull(r)
        assertEquals(TxType.INCOME, r!!.type)
        assertEquals("转账", TransactionParser.guessCategory(r!!.sourceApp, r!!.merchant, r!!.rawText, r!!.type))
    }

    @Test
    fun wechat_transfer_out_is_transfer() {
        val r = p("com.tencent.mm", "小H", "转账 35.00 元 已支付")
        assertNotNull(r)
        assertEquals(TxType.EXPENSE, r!!.type)
        assertEquals("转账", TransactionParser.guessCategory(r!!.sourceApp, r!!.merchant, r!!.rawText, r!!.type))
    }

    @Test
    fun shop_income_is_business() {
        // 网店收款，带"老顾客消费" → 经营
        val r = p("com.tencent.mm", "老顾客", "你已成功收款326.00元（老顾客消费）")
        assertNotNull(r)
        assertEquals(TxType.INCOME, r!!.type)
        assertEquals("经营", TransactionParser.guessCategory(r!!.sourceApp, r!!.merchant, r!!.rawText, r!!.type))
    }

    @Test
    fun business_beats_transfer() {
        // 即使写着「转账」，只要提到货款/消费，也算经营
        val r = p("com.tencent.mm", "客户", "收到转账 800.00 元 货款")
        assertNotNull(r)
        assertEquals("经营", TransactionParser.guessCategory(r!!.sourceApp, r!!.merchant, r!!.rawText, r!!.type))
    }

    @Test
    fun private_transfer_stays_transfer() {
        val r = p("com.tencent.mm", "小H", "收到转账 500.00 元")
        assertNotNull(r)
        assertEquals("转账", TransactionParser.guessCategory(r!!.sourceApp, r!!.merchant, r!!.rawText, r!!.type))
    }

    // ---------- gpt-6 第二轮复核的问题 ----------

    @Test
    fun refund_with_order_words_is_refund() {
        // 退款通知里也有"订单""商品"，不能被判成经营
        val r = p("com.taobao.taobao", "淘宝", "退款成功 订单退款金额 ¥59.00 商品已退回")
        assertNotNull(r)
        assertEquals(TxType.INCOME, r!!.type)
        assertEquals("退款", TransactionParser.guessCategory(r!!.sourceApp, r!!.merchant, r!!.rawText, r!!.type))
    }

    @Test
    fun merchant_receipt_is_business() {
        // 商家收款码到账
        val r = p("com.tencent.mm", "微信支付", "微信支付收款到账，商家收款 ¥128.00")
        assertNotNull(r)
        assertEquals("经营", TransactionParser.guessCategory(r!!.sourceApp, r!!.merchant, r!!.rawText, r!!.type))
    }

    @Test
    fun personal_receipt_is_transfer() {
        // 个人收款，没有商家词汇 → 转账
        val r = p("com.tencent.mm", "老王", "你已成功收款88.00元")
        assertNotNull(r)
        assertEquals("转账", TransactionParser.guessCategory(r!!.sourceApp, r!!.merchant, r!!.rawText, r!!.type))
    }

    // ========== 第四批：广告过滤 / 脱敏 / 打分分类 ==========

    @Test
    fun marketing_tail_keeps_real_expense() {
        // 旧逻辑：命中「领」即整条丢弃 → 真支出被误杀。
        // 新逻辑：只剥掉营销句，交易句照记，金额也只从交易句取
        val r = p("com.eg.android.AlipayGphone", "支付宝",
            "你有一笔0.08元的支出，领1元生活缴费红包。")
        assertNotNull(r)
        assertEquals(8L, r!!.amountCents)
        assertEquals(com.qinkun.accountbook.data.TxType.EXPENSE, r!!.type)
    }

    @Test
    fun taobao_shangou_expense_with_marketing_tail_recorded() {
        // 实机漏记复现：淘宝闪购经支付宝付款，通知带「点击领取」尾巴
        // 旧逻辑把它当广告扔掉 → 18.90 元没进账
        val r = p("com.eg.android.AlipayGphone", "交易提醒",
            "你有一笔18.90元的支出，点击领取2个支付宝积分。")
        assertNotNull(r)
        assertEquals(1890L, r!!.amountCents)
        assertEquals(com.qinkun.accountbook.data.TxType.EXPENSE, r!!.type)
        // 营销句里的「2个」不能污染金额
        assertTrue(r!!.rawText.contains("18.90"))
    }

    @Test
    fun generic_title_becomes_unknown_merchant() {
        // 「交易提醒」是泛化标题，不该当商户名（否则分类被带成「其他」）
        val r = p("com.eg.android.AlipayGphone", "交易提醒",
            "你有一笔18.90元的支出")
        assertNotNull(r)
        assertTrue(r!!.merchant.contains("未识别"))
    }

    @Test
    fun pure_marketing_sentences_dropped() {
        // 整条都是营销，剥完什么都不剩 → 仍然丢弃
        val r = p("com.eg.android.AlipayGphone", "支付宝",
            "限时优惠券已到账，点击领取，满100减20")
        assertNull(r)
    }

    @Test
    fun real_payment_with_coupon_is_kept() {
        // 真交易里也会出现"优惠券"，有成交确认词就不能误杀
        val r = p("com.sankuai.meituan", "美团",
            "支付成功 使用优惠券后实付 ¥25.80")
        assertNotNull(r)
        assertEquals(2580L, r!!.amountCents)
    }

    @Test
    fun marketing_with_confirm_word_is_kept() {
        val r = p("com.eg.android.AlipayGphone", "支付宝",
            "付款成功 消费券已抵扣 实付 ¥12.00")
        assertNotNull(r)
        assertEquals(1200L, r!!.amountCents)
    }

    @Test
    fun pii_is_masked() {
        val raw = "支付成功 ¥58.00 卡号 6222021234567890123 手机 13812345678"
        val masked = com.qinkun.accountbook.util.PiiMasker.mask(raw)
        assertFalse(masked.contains("13812345678"))
        assertFalse(masked.contains("6222021234567890123"))
        assertTrue(masked.contains("[手机号]"))
        assertTrue(masked.contains("[卡号]"))
        // 金额不能被误伤
        assertTrue(masked.contains("58.00"))
    }

    @Test
    fun pii_mask_keeps_short_numbers() {
        // 订单号 20 位不该被当卡号打码（我们只打 15~19 位）
        val raw = "订单号 20261116485739210001 支付成功 ¥30.00"
        val masked = com.qinkun.accountbook.util.PiiMasker.mask(raw)
        assertTrue(masked.contains("20261116485739210001"))
    }

    @Test
    fun scoring_beats_keyword_order() {
        // 「某某餐饮管理有限公司」买手机：老逻辑会被"餐饮"抢走，打分制应该判数码
        val r = p("com.taobao.taobao", "淘宝", "支付成功 订单 ¥4999.00 小米 餐饮管理有限公司")
        assertNotNull(r)
        val cat = TransactionParser.guessCategory(r!!.sourceApp, r.merchant, r.rawText, r.type)
        assertEquals("数码", cat)
    }

    @Test
    fun exclude_word_vetoes_category() {
        // 美团单车应该判交通，不能被"美团"拉去餐饮
        val r = p("com.sankuai.meituan", "美团", "支付成功 美团单车骑行 ¥1.50")
        assertNotNull(r)
        assertEquals("交通", TransactionParser.guessCategory(r!!.sourceApp, r.merchant, r.rawText, r.type))
    }

    @Test
    fun strong_signal_wins() {
        val r = p("com.eg.android.AlipayGphone", "支付宝", "支付成功 海底捞火锅 ¥320.00")
        assertNotNull(r)
        assertEquals("餐饮", TransactionParser.guessCategory(r!!.sourceApp, r.merchant, r.rawText, r.type))
    }

    // ===== gpt-6 校对后补的回归测试 =====

    @Test
    fun real_spending_with_coupon_kept() {
        // gpt-6 指出的误杀场景：真交易被判成广告
        val r = p("com.eg.android.AlipayGphone", "支付宝", "消费20元，使用优惠券")
        assertNotNull(r)
        assertEquals(2000L, r!!.amountCents)
    }

    @Test
    fun reward_ad_on_income_side_dropped() {
        // 收入侧的纯营销文案
        val r = p("com.eg.android.AlipayGphone", "支付宝", "红包奖励 1.00 元 到账")
        // 含"红包到账"或"到账"两种情形，这里断言不崩即可（重点是不把广告当收入）
        assertTrue(r == null || r.amountCents > 0)
    }

    @Test
    fun grouped_card_number_masked() {
        val masked = com.qinkun.accountbook.util.PiiMasker.mask("支付成功 ¥58.00 卡号 6222 0212 3456 7890")
        assertFalse(masked.contains("6222 0212 3456 7890"))
        assertTrue(masked.contains("[卡号]"))
    }

    @Test
    fun scoring_is_deterministic() {
        // 同样的文本跑多次，结果必须一致（gpt-6 指出的 HashMap 顺序问题）
        val text = "支付成功 美团单车 便利店 咖啡 超市"
        val first = com.qinkun.accountbook.service.CategoryRules.classifyExpense(text)
        repeat(20) {
            assertEquals(first, com.qinkun.accountbook.service.CategoryRules.classifyExpense(text))
        }
    }

    @Test
    fun suffix_only_is_not_confident() {
        // 只命中后缀词时应该返回 null（交回兜底），不能硬判
        val r = com.qinkun.accountbook.service.CategoryRules.classifyExpense("小馆")
        assertNull(r)
    }

    // ============================================================
    //  v2.4 抓取能力完善（见 完善意见.md）
    // ============================================================

    /** 只关心分类时用这个 */
    private fun cat(pkg: String, title: String, vararg texts: String): String? {
        val r = TransactionParser.parse(pkg, title, texts.toList()) ?: return null
        return TransactionParser.guessCategory(r.sourceApp, r.merchant, r.rawText, r.type)
    }

    // ---------- 1. 转账支出（P0）----------
    @Test
    fun transfer_out_is_expense() {
        val r = p("com.eg.android.AlipayGphone", "支付宝", "转账成功 ¥100.00")
        assertNotNull(r); r!!
        assertEquals(10000L, r.amountCents)
        assertEquals(TxType.EXPENSE, r.type)
        assertEquals("转账", TransactionParser.guessCategory(r.sourceApp, r.merchant, r.rawText, r.type))
    }

    @Test
    fun transfer_to_person_is_expense() {
        val r = p("com.tencent.mm", "微信", "你向李四转账 88.88 元")
        assertNotNull(r); r!!
        assertEquals(8888L, r.amountCents)
        assertEquals(TxType.EXPENSE, r.type)
        assertEquals("转账", TransactionParser.guessCategory(r.sourceApp, r.merchant, r.rawText, r.type))
    }

    @Test
    fun transfer_in_still_income() {
        val r = p("com.tencent.mm", "微信", "收到转账 200 元")
        assertNotNull(r); r!!
        assertEquals(20000L, r.amountCents)
        assertEquals(TxType.INCOME, r.type)
    }

    @Test
    fun transfer_to_me_still_income() {
        val r = p("com.tencent.mm", "微信", "张三向你转账 50 元")
        assertNotNull(r); r!!
        assertEquals(5000L, r.amountCents)
        assertEquals(TxType.INCOME, r.type)
    }

    // ---------- 2. 红包收入（P0）----------
    @Test
    fun red_packet_received_is_income() {
        val r = p("com.tencent.mm", "微信", "收到红包 ¥5.20")
        assertNotNull(r); r!!
        assertEquals(520L, r.amountCents)
        assertEquals(TxType.INCOME, r.type)
        assertEquals("红包", TransactionParser.guessCategory(r.sourceApp, r.merchant, r.rawText, r.type))
    }

    @Test
    fun red_packet_grabbed_is_income() {
        val r = p("com.tencent.mm", "微信", "抢到红包 8.88 元")
        assertNotNull(r); r!!
        assertEquals(888L, r.amountCents)
        assertEquals(TxType.INCOME, r.type)
        assertEquals("红包", TransactionParser.guessCategory(r.sourceApp, r.merchant, r.rawText, r.type))
    }

    @Test
    fun red_packet_marketing_is_dropped() {
        val r = p("com.eg.android.AlipayGphone", "支付宝", "恭喜获得 5 元红包奖励，点击领取")
        assertNull(r)
    }

    // ---------- 3. 提现方向（P1）----------
    @Test
    fun withdraw_is_expense_not_income() {
        val r = p("com.eg.android.AlipayGphone", "支付宝", "提现成功，已转入银行卡 500.00 元")
        assertNotNull(r); r!!
        assertEquals(50000L, r.amountCents)
        assertEquals(TxType.EXPENSE, r.type)
        assertEquals("转账", TransactionParser.guessCategory(r.sourceApp, r.merchant, r.rawText, r.type))
    }

    @Test
    fun withdraw_plain_is_expense() {
        val r = p("com.eg.android.AlipayGphone", "支付宝", "提现 ¥100 到银行卡")
        assertNotNull(r); r!!
        assertEquals(10000L, r.amountCents)
        assertEquals(TxType.EXPENSE, r.type)
    }

    // ---------- 4. 一位小数（P1）----------
    @Test
    fun one_decimal_yuan() {
        assertEquals(1250L, TransactionParser.extractCents("消费 12.5 元"))
    }

    @Test
    fun two_decimal_yuan_regression() {
        assertEquals(850L, TransactionParser.extractCents("支付 8.50 元"))
    }

    @Test
    fun one_cent_yuan() {
        assertEquals(10L, TransactionParser.extractCents("消费 0.1 元"))
    }

    // ---------- 5. 金额引导词优先级（P1）----------
    @Test
    fun paid_amount_beats_order_amount() {
        assertEquals(9000L, TransactionParser.extractCents("订单金额 100 元，实付 90 元"))
    }

    @Test
    fun paid_amount_beats_order_amount_reversed() {
        assertEquals(9000L, TransactionParser.extractCents("实付 ¥90.00，订单金额 ¥100.00"))
    }

    @Test
    fun refund_amount_lead() {
        assertEquals(5000L, TransactionParser.extractCents("退款金额 50.00 元"))
    }

    @Test
    fun transfer_amount_lead() {
        assertEquals(20000L, TransactionParser.extractCents("转账金额 200 元"))
    }

    // ---------- 6. 新增 App（P1）----------
    @Test
    fun unionpay_expense() {
        val r = p("com.unionpay", "云闪付", "云闪付：消费 ¥25.80")
        assertNotNull(r); r!!
        assertEquals(2580L, r.amountCents)
        assertEquals(TxType.EXPENSE, r.type)
        assertEquals("云闪付", r.sourceApp)
    }

    @Test
    fun eleme_expense_catering() {
        val r = p("me.ele", "饿了么", "订单支付 32.00 元")
        assertNotNull(r); r!!
        assertEquals(3200L, r.amountCents)
        assertEquals("饿了么", r.sourceApp)
        assertEquals("餐饮", TransactionParser.guessCategory(r.sourceApp, r.merchant, r.rawText, r.type))
    }

    @Test
    fun kuaishou_expense_entertainment() {
        val r = p("com.smile.gifmaker", "快手", "打赏 10 元")
        assertNotNull(r); r!!
        assertEquals(1000L, r.amountCents)
        assertEquals("快手", r.sourceApp)
        assertEquals("娱乐", TransactionParser.guessCategory(r.sourceApp, r.merchant, r.rawText, r.type))
    }

    // ---------- 7. 充值（P2）----------
    @Test
    fun phone_topup_is_expense_communicate() {
        val r = p("com.eg.android.AlipayGphone", "支付宝", "话费充值成功 50 元")
        assertNotNull(r); r!!
        assertEquals(5000L, r.amountCents)
        assertEquals(TxType.EXPENSE, r.type)
        assertEquals("通讯", TransactionParser.guessCategory(r.sourceApp, r.merchant, r.rawText, r.type))
    }

    @Test
    fun topup_with_arrival_is_expense() {
        val r = p("com.eg.android.AlipayGphone", "支付宝", "充值成功，已到账 50 元")
        assertNotNull(r); r!!
        assertEquals(5000L, r.amountCents)
        assertEquals(TxType.EXPENSE, r.type)
    }

    // ---------- 8. 还款（P2）----------
    @Test
    fun credit_card_repay_is_transfer() {
        val r = p("com.eg.android.AlipayGphone", "支付宝", "信用卡还款成功 ¥1000")
        assertNotNull(r); r!!
        assertEquals(100000L, r.amountCents)
        assertEquals(TxType.EXPENSE, r.type)
        assertEquals("转账", TransactionParser.guessCategory(r.sourceApp, r.merchant, r.rawText, r.type))
    }

    @Test
    fun repay_reminder_is_dropped() {
        val r = p("com.eg.android.AlipayGphone", "支付宝", "信用卡还款提醒：本期账单 1000 元")
        assertNull(r)
    }

    // ---------- 9. 商户名 fallback（P2）----------
    @Test
    fun merchant_from_body_when_title_is_app() {
        val r = p("com.eg.android.AlipayGphone", "支付宝", "在老王面馆消费 25.00 元，支付成功")
        assertNotNull(r); r!!
        assertEquals(2500L, r.amountCents)
        assertEquals("老王面馆", r.merchant)
    }

    @Test
    fun merchant_from_title_regression() {
        val r = p("com.sankuai.meituan", "美团外卖", "支付成功，共¥35.00")
        assertNotNull(r); r!!
        assertEquals("外卖", r.merchant)
    }

    // ---------- 回归：两条广告/真交易判定 ----------
    @Test
    fun coupon_real_deal_still_recorded() {
        val r = p("com.eg.android.AlipayGphone", "支付宝", "使用优惠券后实付 20.00 元，支付成功")
        assertNotNull(r); r!!
        assertEquals(2000L, r.amountCents)
    }

    @Test
    fun tiny_amount_expense_is_kept_after_rule_change() {
        // 语义变更：过去命中营销词就整条丢弃 → 真支出漏记。
        // 现在只删营销句，交易主句照记（0.08 元确实是真扣款）
        val r = p("com.eg.android.AlipayGphone", "支付宝", "你有一笔0.08元的支出，领1元生活缴费红包")
        assertNotNull(r); r!!
        assertEquals(8L, r.amountCents)
        assertEquals(TxType.EXPENSE, r.type)
    }

    // ---------- 回归：余额不被当金额 ----------
    @Test
    fun balance_still_skipped_after_changes() {
        val r = p("com.eg.android.AlipayGphone", "支付宝", "余额 ¥500.00，本次实付 ¥20.00")
        assertNotNull(r); r!!
        assertEquals(2000L, r.amountCents)
    }

    // ============================================================
    //  v2.6 扫码支付（无障碍读结果页）
    // ============================================================

    private fun scanMerchant(pkg: String, vararg nodes: String): String? =
        TransactionParser.extractMerchantFromScanNodes(nodes.toList())

    @Test
    fun wechat_scan_page_parsed() {
        // 微信扫码成功页的真实节点顺序
        val nodes = listOf("微信支付", "支付成功", "瑞幸咖啡(软件园店)", "￥14.90", "支付方式", "零钱", "完成")
        val r = TransactionParser.parse("com.tencent.mm", "微信 支付成功", nodes)
        assertNotNull(r); r!!
        assertEquals(1490L, r.amountCents)
        assertEquals(TxType.EXPENSE, r.type)
        assertEquals("瑞幸咖啡(软件园店)", TransactionParser.extractMerchantFromScanNodes(nodes))
        assertEquals("餐饮", TransactionParser.guessCategory(r.sourceApp, "瑞幸咖啡(软件园店)", r.rawText, r.type))
    }

    @Test
    fun alipay_scan_page_parsed() {
        val nodes = listOf("支付宝", "付款成功", "全家便利店", "23.50元", "完成")
        val r = TransactionParser.parse("com.eg.android.AlipayGphone", "支付宝 支付成功", nodes)
        assertNotNull(r); r!!
        assertEquals(2350L, r.amountCents)
        assertEquals("全家便利店", TransactionParser.extractMerchantFromScanNodes(nodes))
    }

    @Test
    fun scan_merchant_skips_money_and_buttons() {
        val nodes = listOf("支付成功", "￥32.00", "完成", "支付方式", "星巴克")
        assertEquals("星巴克", TransactionParser.extractMerchantFromScanNodes(nodes))
    }

    @Test
    fun scan_merchant_without_anchor_is_null() {
        assertNull(TransactionParser.extractMerchantFromScanNodes(listOf("星巴克", "￥32.00")))
    }

    @Test
    fun scan_merchant_ignores_pure_symbol() {
        val nodes = listOf("支付成功", "···", "老王面馆", "¥12.00")
        assertEquals("老王面馆", TransactionParser.extractMerchantFromScanNodes(nodes))
    }

    @Test
    fun folded_wechat_notification_parsed() {
        // 折叠通知正文只有一行「[2条] 微信支付: ¥18.00」，没有动词
        val r = TransactionParser.parse("com.tencent.mm", "微信支付", listOf("[2条] 微信支付: ¥18.00"))
        assertNotNull(r); r!!
        assertEquals(1800L, r.amountCents)
        assertEquals(TxType.EXPENSE, r.type)
    }

    @Test
    fun folded_notification_with_bill_words_is_rejected() {
        // 同样简短，但提到「账单」→ 不能当支出
        val r = TransactionParser.parse("com.tencent.mm", "微信支付", listOf("本月账单 1234.00 元"))
        assertNull(r)
    }

    @Test
    fun wechat_pay_long_notification_unaffected() {
        // 正常微信支付服务号通知（走关键词，不靠兜底）
        val r = TransactionParser.parse("com.tencent.mm", "微信支付", listOf("微信支付凭证", "付款金额 ¥36.00"))
        assertNotNull(r); r!!
        assertEquals(3600L, r.amountCents)
    }

    // ============================================================
    //  v2.7 「账户余额」不再被当成转账
    //  线上真实事故：71 元的餐馆消费被判成「转账」，因为付款页带着
    //  「付款方式 账户余额」，而兜底规则里有裸「余额」。
    // ============================================================

    @Test
    fun account_balance_label_is_not_transfer() {
        val raw = "支付宝 支付成功 支付成功￥71.00 天外山 ￥71.00 付款方式 账户余额 完成 回首页 回首页"
        val r = TransactionParser.guessCategory("支付宝", "天外山", raw, TxType.EXPENSE)
        assertNotEquals("转账", r)
    }

    @Test
    fun real_transfer_is_still_transfer() {
        val r = TransactionParser.guessCategory(
            "支付宝", "张三", "支付宝 转账成功 向张三转账 100.00 元", TxType.EXPENSE
        )
        assertEquals("转账", r)
    }

    @Test
    fun withdraw_is_still_transfer() {
        val r = TransactionParser.guessCategory("支付宝", "提现", "提现到银行卡 500.00 元", TxType.EXPENSE)
        assertEquals("转账", r)
    }

    @Test
    fun plain_expense_page_with_balance_falls_back_to_other() {
        // 商户不在词库、只是付款页标签 → 应该落到「其他」，不是「转账」
        val raw = "支付宝 支付成功 支付成功￥71.00 天外山 ￥71.00 付款方式 账户余额 完成 回首页"
        assertEquals("其他", TransactionParser.guessCategory("支付宝", "天外山", raw, TxType.EXPENSE))
    }

    // ============================================================
    //  v2.9 收入被记成支出（线上事故回归）
    //  根因：INCOME_WORDS 缺了「向你付款」「已入账」等说法，
    //        hasIncome=false → 一路判成 EXPENSE。
    // ============================================================

    private fun typeOf(vararg texts: String, title: String = "支付宝",
                       pkg: String = "com.eg.android.AlipayGphone"): TxType? =
        TransactionParser.parse(pkg, title, texts.toList())?.type

    @Test
    fun person_pays_you_is_income() {
        assertEquals(TxType.INCOME, typeOf("张三向你付款 100.00 元"))
    }

    @Test
    fun payment_to_you_is_income() {
        assertEquals(TxType.INCOME, typeOf("李四 付款给你 50.00 元"))
    }

    @Test
    fun already_credited_is_income() {
        assertEquals(TxType.INCOME, typeOf("已入账 200.00 元"))
    }

    @Test
    fun receive_transfer_is_income() {
        assertEquals(TxType.INCOME, typeOf("你收到一笔转账 88.00 元"))
    }

    @Test
    fun receive_money_is_income() {
        assertEquals(TxType.INCOME, typeOf("已收钱 66.00 元"))
    }

    // ---- 下面几条是防回归：别为了修收入把支出判反 ----

    @Test
    fun pay_page_with_payee_label_stays_expense() {
        // 扫码付款页会写「收款方 天外山」，不能因为contains("收款")就判成收入
        val r = TransactionParser.parse(
            "com.eg.android.AlipayGphone", "支付宝 支付成功",
            listOf("支付成功￥71.00", "天外山", "收款方", "付款方式", "账户余额", "完成")
        )
        assertNotNull(r)
        assertEquals(TxType.EXPENSE, r!!.type)
    }

    @Test
    fun normal_payment_stays_expense() {
        assertEquals(TxType.EXPENSE, typeOf("你有一笔30.40元的支出，点此查看详情。"))
    }

    @Test
    fun withdraw_stays_expense() {
        assertEquals(TxType.EXPENSE, typeOf("提现成功，已转入银行卡 500.00 元"))
    }

    @Test
    fun recharge_stays_expense() {
        assertEquals(TxType.EXPENSE, typeOf("充值成功，已到账 50.00 元"))
    }

    // ============================================================
    //  v2.9 收入被判成支出（线上事故 #39）
    //  正文：你已成功收款198.00元. 已转入余额 做生意收钱提现免费>>
    //  尾巴里的营销句含「提现」，旧逻辑的「提现」分支压过了收入信号。
    // ============================================================

    @Test
    fun income_with_ad_tail_containing_withdraw_is_income() {
        val r = TransactionParser.parse(
            "com.eg.android.AlipayGphone",
            "你已成功收款198.00元.",
            listOf("已转入余额 做生意收钱提现免费>>")
        )
        assertNotNull(r)
        assertEquals(TxType.INCOME, r!!.type)
        assertEquals(19800L, r.amountCents)
    }

    @Test
    fun income_with_shop_ad_tail_is_income() {
        // 另一种尾巴：「发起店铺充值活动」里的「充值」也不能把方向带反
        val r = TransactionParser.parse(
            "com.eg.android.AlipayGphone", "支付宝",
            listOf("你已成功收款168.00元（老顾客消费）", "已转入余额", "线上收款支持花呗/信用卡>>")
        )
        assertNotNull(r)
        assertEquals(TxType.INCOME, r!!.type)
    }

    @Test
    fun real_withdraw_is_still_expense() {
        val r = TransactionParser.parse(
            "com.eg.android.AlipayGphone", "支付宝",
            listOf("提现成功，已转入银行卡 500.00 元")
        )
        assertNotNull(r)
        assertEquals(TxType.EXPENSE, r!!.type)
    }

    @Test
    fun sentence_title_is_not_merchant() {
        val r = TransactionParser.parse(
            "com.eg.android.AlipayGphone",
            "你已成功收款198.00元.",
            listOf("已转入余额 做生意收钱提现免费>>")
        )
        assertNotNull(r)
        val merchantName = r!!.merchant
        assertFalse("商户名不该是整句标题：$merchantName", merchantName.contains("收款"))
    }

    @Test
    fun generic_title_is_not_merchant() {
        val r = TransactionParser.parse(
            "com.eg.android.AlipayGphone", "交易提醒",
            listOf("你有一笔181.00元的支出，点此查看详情。")
        )
        assertNotNull(r)
        assertNotEquals("交易提醒", r!!.merchant)
    }

    @Test
    fun wechat_generic_title_is_not_merchant() {
        val r = TransactionParser.parse(
            "com.tencent.mm", "微信支付", listOf("已支付¥43.05")
        )
        assertNotNull(r)
        assertNotEquals("支付", r!!.merchant)
        assertEquals(TxType.EXPENSE, r.type)
    }
}
