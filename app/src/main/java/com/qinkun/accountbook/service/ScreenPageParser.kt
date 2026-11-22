package com.qinkun.accountbook.service

import com.qinkun.accountbook.data.TxType

/**
 * 付款结果页解析（v4.2，纯函数，方便单测）。
 *
 * 为什么从 TransactionParser 里拆出来：
 *   v4.1 之前，无障碍读到的整页文字直接喂给「通知解析器」。
 *   通知解析器有一道「超过 120 字就不是支付通知」的闸 —— 真机上的付款结果页
 *   随便就有 20~40 个节点、两三百字，于是**几乎每一页都被这道闸丢掉**，而且
 *   丢的时候一条日志都不留。这就是「扫码付款一直采集不到，只能手动补」的主因。
 *
 * 页面和通知是两种东西：页面是一堆平铺的短节点（「支付成功」「瑞幸咖啡」「￥14.90」「完成」），
 * 没有句子，按节点逐个看比拼成一句话再用正则抠准得多。
 */
object ScreenPageParser {

    /** 订阅了无障碍事件的 App。必须和 res/xml/accessibility_service_config.xml 的 packageNames 一致 */
    val SCREEN_APPS: Map<String, String> = linkedMapOf(
        "com.tencent.mm" to "微信",
        "com.eg.android.AlipayGphone" to "支付宝",
        "com.sankuai.meituan" to "美团",
        "com.sankuai.meituan.takeoutnew" to "美团外卖",
        "com.taobao.taobao" to "淘宝",
        "com.tmall.wireless" to "天猫",
        "me.ele" to "饿了么",
        "com.jingdong.app.mall" to "京东",
        "com.xunmeng.pinduoduo" to "拼多多",
    )

    /** 纯支付通道：别的 App 下单时也会经过它们（淘宝闪购走支付宝、美团可走微信） */
    val PAY_CHANNELS = setOf("支付宝", "微信", "云闪付")

    sealed class Outcome {
        /** 根本不是结果页（绝大多数页面）：静默忽略，不写日志 */
        object NotResultPage : Outcome()

        /** 像结果页但没记上：要写进「抓取记录」，方便排查 */
        data class Rejected(val reason: String, val rawText: String) : Outcome()

        data class Ok(val parsed: TransactionParser.Parsed) : Outcome()
    }

    // ------------------------------------------------------------------ 闸门

    private val ANCHORS_COMMON = listOf("支付成功", "付款成功", "交易成功", "下单成功", "订单支付成功")

    /** 「已支付」太泛（订单列表里一排都是），只在微信/支付宝的结果页里认 */
    private val ANCHORS_PAY_ONLY = listOf("已支付", "已付款")

    /** 结果页一定有的东西：按钮、订单号、支付方式 */
    private val RESULT_HINTS = listOf(
        "完成", "返回商家", "返回商户", "查看订单", "订单详情", "继续逛", "返回首页",
        "订单号", "交易单号", "支付时间", "付款方式", "支付方式", "查看详情",
    )

    /** 「强按钮」：有它基本就是刚付完款的那一页，可以压过软黑名单 */
    private val STRONG_BUTTONS = setOf("完成", "返回商家", "返回商户")

    /** 硬黑名单：命中就一定不是「刚付完款」，是在翻账单、订单列表或聊天 */
    private val HARD_BLOCK = listOf(
        "全部账单", "搜索账单", "月账单", "查看全部账单", "筛选项", "收支明细", "零钱明细",
        "本月支出", "本月收入", "交易记录", "交易明细", "转账记录",
        "发消息", "按住说话", "朋友圈", "看一看", "搜一搜",
        // 购物 App 的订单列表 / 订单详情
        "全部订单", "我的订单", "待付款", "待收货", "待评价", "申请售后", "退款/售后",
        "确认收货", "再来一单", "再次购买", "订单列表",
    )

    /**
     * 软黑名单：单独出现多半不是结果页，但微信付款成功页常带「关注公众号」勾选框、
     * 支付宝结果页常带「账单详情」入口 —— 有强按钮时放行。
     * v4.1 之前「公众号」在硬黑名单里，微信扫码付款页**每一页都被它拦掉**。
     */
    private val SOFT_BLOCK = listOf("公众号", "账单详情")

    private fun anchorsFor(pkg: String) =
        if (pkg in PAY_PKGS) ANCHORS_COMMON + ANCHORS_PAY_ONLY else ANCHORS_COMMON

    private val PAY_PKGS = setOf("com.tencent.mm", "com.eg.android.AlipayGphone")

    /** 页面像不像结果页（不管能不能解析出金额） */
    fun isResultPage(pkg: String, nodes: List<String>): Boolean = gate(pkg, nodes) == null

    /** null = 通过；否则返回没通过的原因 */
    private fun gate(pkg: String, nodes: List<String>): String? {
        if (nodes.isEmpty()) return "空页面"
        val joined = nodes.joinToString(" ")
        val anchors = anchorsFor(pkg)
        if (nodes.none { n -> anchors.any { n.contains(it) } }) return "没有支付成功字样"
        if (RESULT_HINTS.none { joined.contains(it) }) return "没有结果页特征"
        HARD_BLOCK.firstOrNull { joined.contains(it) }?.let { return "像账单/订单列表/聊天（$it）" }
        val strong = nodes.any { it.trim() in STRONG_BUTTONS }
        if (!strong) SOFT_BLOCK.firstOrNull { joined.contains(it) }?.let { return "像账单/文章（$it）" }
        return null
    }

    // ------------------------------------------------------------------ 金额

    /** 一个节点就是一个金额：「￥14.90」「-71.00」「23.50元」「¥ 1,299」 */
    private val AMOUNT_NODE = Regex("""^[-−]?\s*[¥￥]?\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)\s*元?$""")
    private val CURRENCY_IN = Regex("""[¥￥]\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""")

    /** 金额引导词：节点里有它，后面（同节点或下一节点）就是实付金额 */
    private val AMOUNT_LABELS = listOf("实付款", "实付", "支付金额", "付款金额", "实际支付", "合计", "订单金额")

    /** 出现在金额前后的这些词说明它不是实付金额 */
    private val NOT_PAID = Regex("""优惠|立减|红包|原价|已省|节省|省了|积分|返现|返利|券|抵扣|余额|可用|额度|满\d|运费|配送费|打包费|共减""")

    private fun toCents(s: String): Long? {
        val d = s.replace(",", "").toDoubleOrNull() ?: return null
        if (d <= 0.0 || d > 1_000_000.0) return null
        return Math.round(d * 100)
    }

    /**
     * 节点级金额提取：
     *   1. 带引导词的（「实付 ¥28.80」「实付款」+ 下一节点「¥28.80」）
     *   2. 锚点之后第一个「整个节点就是金额」且看起来像钱（带 ¥、带小数或带「元」）的
     *   3. 锚点之后任意节点里的「¥X」
     * 前后节点含「优惠/立减/原价/运费…」的跳过。
     * 「¥」和「14.90」拆成两个节点的情况先合并。
     */
    fun extractAmount(nodes: List<String>, anchorIdx: Int): Long? {
        val ns = mergeSplitCurrency(nodes)
        val start = anchorIdx.coerceAtLeast(0)

        // 1. 引导词
        for (i in ns.indices) {
            val n = ns[i]
            val label = AMOUNT_LABELS.firstOrNull { n.contains(it) } ?: continue
            if (NOT_PAID.containsMatchIn(n.substringBefore(label))) continue
            val after = n.substringAfter(label)
            CURRENCY_IN.find(after)?.let { m -> toCents(m.groupValues[1])?.let { return it } }
            Regex("""([0-9][0-9,]*\.[0-9]{1,2})""").find(after)?.let { m -> toCents(m.groupValues[1])?.let { return it } }
            ns.getOrNull(i + 1)?.let { nx ->
                AMOUNT_NODE.find(nx.trim())?.let { m -> toCents(m.groupValues[1])?.let { return it } }
            }
        }

        fun ok(i: Int): Boolean {
            val prev = ns.getOrNull(i - 1).orEmpty()
            return !NOT_PAID.containsMatchIn(ns[i]) && !NOT_PAID.containsMatchIn(prev)
        }

        // 2. 整节点金额（先锚点之后，再整页）
        for (range in listOf(start until ns.size, ns.indices)) {
            for (i in range) {
                val t = ns[i].trim()
                val m = AMOUNT_NODE.find(t) ?: continue
                val moneyLike = t.contains('¥') || t.contains('￥') || t.contains('.') || t.endsWith("元")
                if (!moneyLike || !ok(i)) continue
                toCents(m.groupValues[1])?.let { return it }
            }
        }

        // 3. 节点内的 ¥X
        for (i in start until ns.size) {
            if (!ok(i)) continue
            CURRENCY_IN.find(ns[i])?.let { m -> toCents(m.groupValues[1])?.let { return it } }
        }
        return null
    }

    private fun mergeSplitCurrency(nodes: List<String>): List<String> {
        val out = ArrayList<String>(nodes.size)
        var i = 0
        while (i < nodes.size) {
            val t = nodes[i].trim()
            val next = nodes.getOrNull(i + 1)?.trim()
            if ((t == "¥" || t == "￥" || t == "-¥" || t == "-￥") && next != null && next.firstOrNull()?.isDigit() == true) {
                out.add(t + next); i += 2
            } else {
                out.add(nodes[i]); i++
            }
        }
        return out
    }

    // ------------------------------------------------------------------ 其他信息

    private val PAY_METHODS = listOf(
        "零钱通", "零钱", "余额宝", "账户余额", "花呗", "信用卡", "储蓄卡", "银行卡", "亲情卡", "数字人民币", "美团月付", "白条",
    )

    /** 结果页上的广告条，不进原文 */
    private val AD_NODE = Regex("""领取|立即|去使用|抽奖|开通|广告|限时|红包|点击|查看更多|升级""")

    private val TIME_NODE = Regex("""\d{4}[-/年]\d{1,2}[-/月]\d{1,2}|\d{1,2}:\d{2}""")

    /** 商户名候选里额外要排除的（App 名、页面标题） */
    private val MERCHANT_NOISE = listOf(
        "美团", "美团外卖", "淘宝", "天猫", "饿了么", "京东", "拼多多", "微信", "支付宝",
        "关注公众号", "订单详情", "查看订单", "继续逛逛", "返回首页", "返回商家", "返回商户",
        "支付金额", "付款金额", "实付", "实付款", "订单号", "交易单号", "支付时间", "收银台",
    )

    /** 锚点前面常见的页面标题 / 导航按钮 */
    private val BACKWARD_NOISE = Regex("""支付|付款|返回|关闭|结果|收银|订单|完成|详情""")

    /** 按节点挑商户名：锚点之后第一个像名字的 */
    fun extractMerchant(nodes: List<String>, anchorIdx: Int): String? {
        val fromScan = TransactionParser.extractMerchantFromScanNodes(
            nodes.filterNot { n ->
                MERCHANT_NOISE.any { n.trim() == it } || TIME_NODE.containsMatchIn(n) ||
                    AD_NODE.containsMatchIn(n) || PAY_METHODS.any { n.contains(it) }
            }
        )
        if (fromScan != null) return fromScan
        // 有些页面商户名在锚点前面（支付宝：「天外山」「付款成功」「¥71.00」）
        for (i in (anchorIdx - 1) downTo 0) {
            val raw = nodes[i].trim()
            if (raw.length !in 2..24) continue
            if (MERCHANT_NOISE.any { raw == it } || AD_NODE.containsMatchIn(raw) || TIME_NODE.containsMatchIn(raw)) continue
            if (PAY_METHODS.any { raw.contains(it) }) continue
            if (raw.any { it == '¥' || it == '￥' } || raw.none { it.isLetter() }) continue
            if (AMOUNT_NODE.matches(raw)) continue
            if (BACKWARD_NOISE.containsMatchIn(raw)) continue
            return raw
        }
        return null
    }

    /** 支付方式：「零钱」「花呗」「招商银行信用卡(1234)」 */
    fun extractPayMethod(nodes: List<String>): String? =
        nodes.map { it.trim() }.firstOrNull { n ->
            n.length in 2..24 && PAY_METHODS.any { n.contains(it) } && !AD_NODE.containsMatchIn(n)
        }

    /**
     * 原文：整页节点去掉广告条和重复项，按顺序拼起来，最长 500 字。
     * 尽量完整，方便事后点开核对是哪一页、哪一笔。
     */
    fun buildRawText(appName: String, nodes: List<String>): String {
        val kept = nodes.map { it.trim() }
            .filter { it.isNotEmpty() && it.length <= 60 && !AD_NODE.containsMatchIn(it) }
            .distinct()
        return ("[$appName 付款页] " + kept.joinToString(" / ")).take(500)
    }

    // ------------------------------------------------------------------ 主入口

    fun analyze(pkg: String, nodes: List<String>): Outcome {
        val appName = SCREEN_APPS[pkg] ?: return Outcome.NotResultPage
        val clean = nodes.map { it.trim() }.filter { it.isNotEmpty() }

        // 没有成功锚点的页面占 99%：直接静默返回，不浪费日志
        val anchors = anchorsFor(pkg)
        val anchorIdx = clean.indexOfFirst { n -> anchors.any { n.contains(it) } }
        if (anchorIdx < 0) return Outcome.NotResultPage

        val raw = buildRawText(appName, clean)
        gate(pkg, clean)?.let { return Outcome.Rejected(it, raw) }

        val cents = extractAmount(clean, anchorIdx) ?: return Outcome.Rejected("结果页上没找到金额", raw)

        val merchant = extractMerchant(clean, anchorIdx)
            ?: if (pkg == "com.taobao.taobao" && clean.any { it.contains("闪购") }) "淘宝闪购" else appName

        val scene = when {
            pkg == "com.taobao.taobao" && clean.any { it.contains("闪购") } -> "淘宝闪购"
            pkg in PAY_PKGS -> "扫码/付款码"
            else -> "$appName 下单"
        }
        val note = listOfNotNull(scene, extractPayMethod(clean)).distinct().joinToString(" · ")

        return Outcome.Ok(
            TransactionParser.Parsed(
                amountCents = cents,
                type = TxType.EXPENSE,
                sourceApp = appName,
                merchant = merchant,
                rawText = raw,
                note = note,
            )
        )
    }
}
