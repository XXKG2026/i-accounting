package com.qinkun.accountbook.service

import com.qinkun.accountbook.data.TxType

/**
 * 把一条通知的文本解析成一笔账。
 * 纯函数，无 io，方便单测。
 */
object TransactionParser {

    /** 包名 → App 显示名 */
    val APP_NAMES = mapOf(
        "com.eg.android.AlipayGphone" to "支付宝",
        "com.taobao.taobao" to "淘宝",
        "com.taobao.idlefish" to "闲鱼",
        "com.sankuai.meituan" to "美团",
        "com.dianping.v1" to "大众点评",
        "com.jingdong.app.mall" to "京东",
        "com.xunmeng.pinduoduo" to "拼多多",
        "com.ishansong" to "闪送",
        "com.tencent.mm" to "微信",
        "com.xingin.xhs" to "小红书",
        "com.ss.android.ugc.aweme" to "抖音",
        "com.ss.android.article.news" to "今日头条",
        "com.unionpay" to "云闪付",
        "me.ele" to "饿了么",
        "com.smile.gifmaker" to "快手",
        // 银行 App 待纳入：包名必须逐个在真机上核实，猜错等于没加还会留错代码。
        // 候选：招商银行、建行、工行、农行、中行、交行、邮储、浦发、广发、民生、兴业、平安。
        // 银行通知文案一般是「您尾号 1234 的储蓄卡消费支出人民币 12.34 元」，
        // 含「消费/支出」+「12.34 元」，现有管线能直接处理，不需要专属关键词。
    )

    /** 支出关键词 */
    private val EXPENSE_WORDS = listOf(
        "支付成功", "付款成功", "已付款", "成功付款", "支付完成",
        "消费", "扣款", "已支付", "订单支付", "扣费", "支出",
        // 「实付」是支付宝/美团最高频的金额引导词，必须认
        "实付", "已实付", "付款金额", "支付金额", "本次消费", "扣款金额",
        // 转出（带方向，别用裸「转账」，否则和「收到转账」互相干扰）
        "转账成功", "已转账", "转账支出", "转出",
        // 充值（「充值成功，已到账」里的"到账"指服务到账，不是收款）
        "充值成功", "已充值",
        // 还款（「还款提醒」在 EXCLUDE_WORDS 里，不冲突）
        "还款成功", "已还款",
        // 提现：动作本身就是钱出去（方向由类型特判保证，不靠这里）
        "提现",
        // 打赏：直播/内容平台的典型支出，文案往往只有「打赏 10 元」
        "打赏",
        // 代付/帮付：钱一定是我出的，且有「成功」字样的多是已完成
        "代付", "帮付", "代付款", "为他人付款"
    )

    /** 收入关键词 */
    private val INCOME_WORDS = listOf(
        "收款成功", "已收款", "到账", "退款成功", "已退款", "收入", "转入",
        // 微信/支付宝转账的高频说法
        "收到转账", "成功收款", "你已成功收款", "向你转账", "已到账",
        // 收红包（「收到红包」同时是营销过滤的豁免词，不会被误杀）
        "收到红包", "抢到红包", "红包入账",
        // v2.9 补：这几种说法线上都出现过，原来一个都没覆盖 → 被判成支出
        "向你付款", "付款给你", "已入账", "收到一笔", "收款到账", "已收钱",
        // 「收到」单独用太宽（"收到货"），必须跟钱相关的词连用
        "收到转账", "收到的转账", "收到货款", "收到款项",
    )

    /** 排除词：命中就不记账（避免误记） */
    private val EXCLUDE_WORDS = listOf(
        // 这些是"根本不该记账"的通知类型，命中即丢
        "余额提醒", "账单提醒", "还款提醒",
        "待支付", "请支付", "立即支付",
        "会员到期", "签到", "抽奖", "为你推荐", "猜你喜欢",
        // 退款只有「成功/已退款」才是真钱到账；中间态一律不记，
        // 否则「退款申请已提交 19.90 元」会被当成一笔支出/收入。
        "退款申请", "申请退款", "退款中", "退款处理中", "同意退款", "退款审核",
        // 余额宝/零钱通互转：左口袋进右口袋，既不是消费也不是收入，
        // 记进来只会把月度支出统计抬高。
        "转入余额宝", "转出余额宝", "零钱通转入", "转入零钱通", "理财通转入",
    )

    /**
     * 「软排除词」：单独出现时多半是广告/未成交，但如果同一条文案里
     * 有明确的成交确认，就说明它是真交易的附属说明（比如「支付成功 20 元，
     * 获得 1 次抽奖机会」），不能因此整条丢弃。
     */
    private val SOFT_EXCLUDE = listOf("抽奖", "待支付", "请支付", "立即支付")


    /**
     * 成交确认词 —— 出现这些，说明这是一笔**真实交易**，
     * 此时即使文案里带"优惠券""立减"，也不能当广告丢掉。
     * （例如「使用优惠券后实付 20.00 元」是真交易）
     */
    private val DEAL_CONFIRM = listOf(
        "支付成功", "付款成功", "已支付", "已付款", "支付完成", "付款完成",
        "交易成功", "实付", "扣款", "已扣款",
        // ⚠️ 这里不要放裸「消费」：「消费满 100 送抽奖」是纯广告也会命中它，
        // 导致软排除豁免被滥用。「消费 20 元」这种真交易由 hasTradeStatement 兜住
        // （交易动词 + 金额同时出现才算）。
    )

    /**
     * 营销文案特征词。
     *
     * 为什么需要：支付宝通知里塞满广告，形如
     *   「你有一笔0.08元的支出，领1元生活缴费红包。」
     * 里面有金额、有"支出"，会被当成一笔 8 分钱的消费记下来 —— 纯垃圾。
     * 而且"生活缴费"四个字还会把它分到生活缴费类。
     *
     * 处理方式：命中营销词、且**没有**成交确认词 → 整条丢弃。
     */
    private val MARKETING_MARKERS = listOf(
        "领取", "领1元", "领2元", "领3元", "领5元", "领红包", "点击领取", "立即领取",
        "恭喜获得", "点击参与", "点击查看", "点击立即",
        "优惠券", "消费券", "立减", "随机立减", "满减", "满赠", "红包奖励",
    )

    /**
     * 标题去掉 App 名之后只剩下这些词，就等于没留下商户信息。
     * 例：「微信支付」去掉「微信」剩「支付」；不滤掉的话商户名会变成「支付」，
     * 分类也跟着乱（线上实测过）。
     */
    private val TITLE_NOISE = listOf(
        "支付", "付款", "收款", "转账", "交易", "提醒", "通知",
        "成功", "失败", "消息", "服务", "助手",
    )

    /** 泛化通知标题：它们不是商户名，直接当 merchant 会把分类带偏 */
    private val GENERIC_TITLES = listOf(
        "交易提醒", "支付助手", "收钱提醒助手正在为您服务", "服务通知", "通知",
        "支付宝", "微信", "微信支付", "云闪付",
    )

    /** 句读切分：营销句常以逗号/句号挂在交易主句后面 */
    private val SENTENCE_SPLIT = Regex("""[，,。;；!！\n]""")

    /**
     * 把「营销句」从通知文本里剥掉，而不是整条丢弃。
     *
     * 实测形态：「你有一笔18.90元的支出，点击领取2个支付宝积分。」
     * 句①是真交易，句②是广告尾巴。旧逻辑命中「领取」即整条 return null
     * → 真支出被误杀（实测漏记 18.90 元）。
     *
     * 做法：按标点切句，命中营销词的句子删掉，剩下的拼回去继续解析；
     * 金额也只从交易句里取，营销句里的「领1元红包」不会污染金额。
     */
    fun stripMarketingSentences(text: String): String {
        if (MARKETING_MARKERS.none { text.contains(it) }) return text
        val kept = text.split(SENTENCE_SPLIT).filterNot { isMarketingSentence(it) }
        return kept.joinToString("，").trim()
    }

    /** 交易动词 / 金额外观，用来识别「交易主句」 */
    private val TRADE_VERB = Regex("""支出|支付|付款|消费|收款|转账|退款""")
    private val AMOUNT_LIKE = Regex("""[¥￥]\s*[0-9]|[0-9]+(\.[0-9]{1,2})?\s*元""")

    /**
     * 「支付宝」里含「支付」、「微信支付」里含「支付」—— 测交易动词前必须先把这些词抹掉，
     * 否则「支付宝 恭喜获得 5 元红包奖励」会被当成交易主句放行（实测踩过）。
     */
    private val VERB_NOISE = listOf("支付宝", "微信支付", "云闪付", "支付助手", "付款码", "收款码")

    private fun hasTradeStatement(seg: String): Boolean {
        var t = seg
        VERB_NOISE.forEach { t = t.replace(it, "") }
        return TRADE_VERB.containsMatchIn(t) && AMOUNT_LIKE.containsMatchIn(t)
    }

    /**
     * 判断「单个句子」是不是纯营销句 —— 只有它才该被删。
     *
     * 三条豁免（任一成立即保留整句）：
     *   1. 含成交确认词（「实付」「支付成功」）—— 「使用优惠券后实付 20 元」是真交易
     *   2. 同时含交易动词和金额 —— 「你有一笔18.90元的支出」是真支出
     *   3. 不含营销词 —— 根本不参与判断
     *
     * 只有「命中营销词、又不带成交确认、也不是交易主句」的句子才是广告尾巴，例如
     * 「点击领取2个支付宝积分」「领1元生活缴费红包」。
     */
    private fun isMarketingSentence(seg: String): Boolean {
        if (MARKETING_MARKERS.none { seg.contains(it) }) return false
        if (DEAL_CONFIRM.any { seg.contains(it) }) return false
        if (hasTradeStatement(seg)) return false
        return true
    }

    /**
     * 金额正则，严格按语义优先级排列。
     * 第一优先：实付/支付/付款/收款/到账/金额 这类词后面的数字（最可信）
     * 第二优先：带 ¥ 的
     * 第三优先：X.XX 元
     * 绝不单独抓裸的 12.34（会把订单号、比例、时间当金额）
     */
    /**
     * 阶段一的「金额引导词 + 权重」。
     *
     * 为什么不能只按出现位置取：通知里常有「订单金额 100 元，实付 90 元」，
     * 按位置取会抓到 100。按权重取才抓得到 90。
     *
     * 权重相同时退化为按位置先后。
     */
    private val AMOUNT_LEADS: List<Pair<String, Int>> = listOf(
        "实付" to 100, "实收" to 100,
        "实际支付" to 90, "付款金额" to 90, "支付金额" to 90, "收款金额" to 90,
        "转账金额" to 80, "退款金额" to 80, "到账金额" to 80, "扣款金额" to 80,
        "本次消费" to 70, "消费金额" to 70, "收入金额" to 70, "支出金额" to 70,
        "共支付" to 90, "已支付" to 90, "合计支付" to 85, "总计" to 60,
        "提现金额" to 70, "红包金额" to 70,
        "共计" to 60, "合计" to 60,
        "订单金额" to 50,
    )

    /**
     * AMOUNT_LEADS 的正则预编译版。
     *
     * 曾经每次解析都在循环里 new Regex —— 一条通知要现编译 20 个正则，
     * 而通知监听是高频回调，纯属白烧 CPU。正则与权重一起编译一次即可。
     */
    private val AMOUNT_LEAD_REGEXES: List<Pair<Int, Regex>> = AMOUNT_LEADS.map { (w, p) ->
        p to Regex(
            Regex.escape(w) + """[^0-9￥¥]{0,10}[¥￥]?\s*([0-9,]+(?:\.[0-9]{1,2})?)"""
        )
    }

    /** 阶段一之后的正则（靠货币符号或「元」推断） */
    private val AMOUNT_REGEXES = listOf(
        // 阶段二：带货币符号的
        Regex("""(?<![0-9.,])[¥￥]\s*([0-9,]+(?:\.[0-9]{1,2})?)"""),
        // 阶段三：X.X ~ X.XX 元（一位小数也要认，「消费 12.5 元」）
        Regex("""(?<![0-9.])([0-9,]+\.[0-9]{1,2})\s*元"""),
        // 阶段四：整数元
        Regex("""(?<![0-9.])([0-9]{1,7})\s*元"""),
    )

    data class Parsed(
        val amountCents: Long,
        val type: TxType,
        val sourceApp: String,
        val merchant: String,
        val rawText: String,
    )

    /**
     * 主入口：返回 null 表示这条通知不该记账。
     *
     * [onFail] 会带上「为什么放弃」的原因，给「抓取记录」页用 ——
     * 以前漏记是黑盒，现在能一眼看出是没收到、还是收到了没认出、还是被判定成广告。
     */
    fun parse(
        pkg: String,
        title: String,
        texts: List<String>,
        onFail: (String) -> Unit = {},
    ): Parsed? {
        val app = APP_NAMES[pkg]
        if (app == null) { onFail("不在支持的 App 名单里"); return null }

        val blob = (listOf(title) + texts).joinToString(" ").replace("\u00A0", " ").trim()
        if (blob.isBlank()) { onFail("通知内容为空"); return null }

        // 营销句剥离后的正文：后续所有判定都用它，rawText 仍保留原文
        val body = stripMarketingSentences(blob)

        // 排除：软排除词遇到成交确认时放行（「支付成功 20 元，获得 1 次抽奖机会」）
        val hitExclude = EXCLUDE_WORDS.firstOrNull { body.contains(it) }
        if (hitExclude != null) {
            val exempt = hitExclude in SOFT_EXCLUDE && DEAL_CONFIRM.any { body.contains(it) }
            if (!exempt) { onFail("命中排除词「$hitExclude」"); return null }
        }

        // 「向 XXX 转账」是明确的转出；「向你转账」是别人转给我，不算
        val transferOut = Regex("""向.{1,20}?转账""").containsMatchIn(body) &&
                !body.contains("向你转账")

        // 少数极简通知只有「微信支付 / ¥18.00」这种，没有任何动词。
        // 兜底认成支出，但前置条件卡得很死（见 shortPayHint），宁可漏记也不误记。
        val shortPayHint =
            (pkg == "com.tencent.mm" || pkg == "com.eg.android.AlipayGphone") &&
                (title.contains("微信支付") || title.contains("支付宝") || title.contains("i记账 支付成功")) &&
                texts.size <= 3 &&
                EXCLUDE_WORDS.none { body.contains(it) } &&
                listOf("账单", "明细", "余额", "额度", "还款", "收益", "理财", "积分", "退款").none {
                    body.contains(it)
                }

        val hasExpense = EXPENSE_WORDS.any { body.contains(it) } || transferOut || shortPayHint
        val hasIncome = INCOME_WORDS.any { body.contains(it) }
        if (!hasExpense && !hasIncome) { onFail("没找到支付/收款关键词"); return null }

        val cents = extractCents(body)
        if (cents == null) { onFail("没找到金额"); return null }
        if (cents <= 0L || cents > 100_000_000L) { onFail("金额异常（$cents 分）"); return null }

        // 类型判定。三个特判要在「收入优先」之前，否则方向会记反：
        //   提现  —— 「提现成功，已转入银行卡」含「转入」，但钱是出去的
        //   充值  —— 「充值成功，已到账 50 元」含「到账」，但钱是出去的
        //   转出  —— 「向 XX 转账」不带「收到」
        // 其余同时命中时以收入为准（"退款成功" 通常也含"支付"）
        // 收入强信号：出现这些词，基本可以确定钱是进来的。
        // 单独拎出来是因为付款页常带「收款方」「对方收款」这类字样，
        // 只靠 hasIncome 的话会被 expense 词抢走方向。
        // 代付：钱是我出的。但必须排除「张三代付成功，你已收款 50 元」这种
        // 别人替我付、我收钱的场景 —— 出现收入信号就不算我出钱。
        val proxyPay = listOf("代付", "帮付", "代付款", "为他人付款").any { body.contains(it) } &&
            listOf("你已收款", "收款成功", "已收款", "到账", "入账", "收到转账", "转账给你")
                .none { body.contains(it) }

        val incomeStrong = listOf(
            "收款成功", "已收款", "你已成功收款", "成功收款", "向你转账", "收到转账",
            "向你付款", "付款给你", "已入账", "收款到账", "已收钱",
            "退款成功", "已退款", "红包入账",
            // DeepSeek 复核补充：这些说法之前都没显式覆盖，全靠 hasIncome 兜底，
            // 而兜底会被 expense 词抢走方向
            "退款到账", "收款到账", "红包已存入", "已存入零钱",
            "AA收款", "AA 收款", "群收款", "转账给你", "还给你",
        ).any { body.contains(it) }

        // 「提现」必须带动作词才作数。
        // 线上事故：收入通知「你已成功收款198.00元. 已转入余额 做生意收钱提现免费>>」
        // 尾巴上的营销句含「提现」，旧逻辑拿裸「提现」做判据，把 198 元收入记成了支出。
        val withdrawReal = listOf("提现成功", "已提现", "提现到", "提现金额", "提现申请")
            .any { body.contains(it) }

        val type = when {
            // 代付是我出钱，先于收入信号判断（「代付成功」里也可能带「成功收款」）
            proxyPay -> TxType.EXPENSE

            // 收入强信号优先级最高：「你已成功收款」这种明确到不能再明确，
            // 不能被营销尾巴里的任何词推翻。
            incomeStrong -> TxType.INCOME

            // 真提现：钱出去（「提现成功，已转入银行卡」含「转入」但方向是出）
            withdrawReal -> TxType.EXPENSE

            listOf("充值成功", "已充值").any { body.contains(it) } -> TxType.EXPENSE

            hasIncome -> TxType.INCOME
            else -> TxType.EXPENSE
        }

        // 营销过滤：营销句已在 body 阶段剥离，这里只兜「整条都是广告」的情况。
        // 收入侧维持原逻辑：只拦「红包奖励」这类纯营销文案（红包入账是真钱）。
        if (body.isBlank()) { onFail("整条都是营销文案（已剥离）"); return null }
        if (type == TxType.INCOME &&
            listOf("红包奖励", "奖励金", "返利券", "优惠券").any { body.contains(it) } &&
            !listOf("红包到账", "红包收入", "转入", "收到红包").any { body.contains(it) }
        ) { onFail("疑似营销奖励文案"); return null }

        // 商户名：先取标题（去掉 App 名和前后标点）
        val fromTitleRaw = title
            .replace(Regex("^【[^】]*】"), "")
            .replace(app, "")
            .replace(Regex("^[\\s:：\\-—|]+"), "")
            .replace(Regex("[\\s:：\\-—|]+$"), "")
            .take(40)

        // 去完 App 名只剩「支付」「提醒」这类泛义词 → 等于没信息，交给正文去猜
        val fromTitle = if (fromTitleRaw.isEmpty() || fromTitleRaw in TITLE_NOISE) "" else fromTitleRaw

        // 标题是「交易提醒」这类泛化词时它不构成商户名 —— 宁可标未识别，也别把分类带偏
        // 标题给不出商户（空 / 只有 App 名）时，回到正文里找
        // 标题本身是一句完整的话（带金额）时，它不是商户名。
        // 线上事故：标题「你已成功收款198.00元.」被当成商户名，分类也跟着跑偏。
        val titleIsSentence = AMOUNT_LIKE.containsMatchIn(fromTitle) || fromTitle.length > 22

        val merchant = when {
            fromTitle in GENERIC_TITLES || titleIsSentence ->
                extractMerchantFromBody(body) ?: "$app（未识别商户）"
            fromTitle.isBlank() || fromTitle == app ->
                extractMerchantFromBody(body) ?: app
            else -> fromTitle
        }

        return Parsed(cents, type, app, merchant, blob)
    }

    /** 正文里的商户线索：「在 X 消费」「向 X 付款」「X 收款」 */
    private val MERCHANT_PATTERNS = listOf(
        Regex("""在([^，,。；;\s]{2,20}?)(?:消费|支付|付款|买单)"""),
        Regex("""向([^，,。；;\s]{2,20}?)(?:付款|转账)"""),
        Regex("""([^，,。；;\s0-9]{2,20}?)收款"""),
    )

    /** 扫码结果页上不属于商户名的词 */
    private val SCAN_NOISE = listOf(
        "完成", "确定", "返回", "关闭", "查看详情", "商家服务", "账单详情", "账单",
        "支付方式", "付款方式", "收款方式", "优惠", "优惠券", "积分", "零钱", "余额宝",
        "花呗", "银行卡", "信用卡", "更多", "详情", "支付成功", "付款成功", "交易成功",
        "已支付", "微信支付", "支付宝", "扫一扫", "付款码", "收款方", "付款方",
        // 结果页上常插的广告条，容易被当成商户名
        "领取", "立减", "立减金", "红包", "去使用", "立即使用", "查看", "广告",
        "优惠活动", "限时", "开通", "抽奖", "客服", "投诉", "申请退款",
    )

    /**
     * 从扫码结果页的**平铺节点文本**里提取商户名。
     *
     * 为什么单开一个方法：这类页面是 ["支付成功", "星巴克", "￥32.00", "完成"] 这种
     * 平铺结构，没有「在 X 消费」的介词，正则模板抓不到。
     *
     * 做法：定位「支付成功」这类锚点，往后找第一个既不像金额、也不像按钮的词。
     */
    fun extractMerchantFromScanNodes(texts: List<String>): String? {
        val anchorIdx = texts.indexOfFirst { t -> SCAN_ANCHORS.any { t.contains(it) } }
        if (anchorIdx < 0) return null

        for (i in anchorIdx + 1 until texts.size) {
            val raw = texts[i].trim()
            if (raw.length !in 2..24) continue
            // 金额
            if (raw.any { it == '¥' || it == '￥' }) continue
            if (raw.matches(Regex("""[\\d.,\s]+"""))) continue
            if (raw.matches(Regex("""[\\d.,]+\\s*元"""))) continue
            // 按钮/标签词
            if (SCAN_NOISE.any { raw == it || raw.startsWith(it) }) continue
            // 纯符号
            if (raw.none { it.isLetter() }) continue
            return raw
        }
        return null
    }

    private val SCAN_ANCHORS = listOf("支付成功", "付款成功", "已支付", "交易成功")

    /**
     * 从正文里猜商户名。
     * 只用来兜底（标题没给出商户时），宁缺毋滥 —— 猜不出就返回 null。
     */
    fun extractMerchantFromBody(text: String): String? {
        for (re in MERCHANT_PATTERNS) {
            val m = re.find(text) ?: continue
            val name = m.groupValues[1].trim()
            // 排掉「你」「我」这类代词和纯数字
            if (name.length < 2) continue
            if (name in listOf("你", "我", "他", "她", "它", "本店", "该店")) continue
            if (name.all { it.isDigit() }) continue
            return name
        }
        return null
    }

    /** 从文本里抓金额，返回「分」 */
    fun extractCents(text: String): Long? {
        // 阶段一：命中金额引导词的数字最可信。按「权重降序、位置升序」取第一个
        var bestPrio = -1
        var bestPos = Int.MAX_VALUE
        var bestCents: Long? = null

        for ((prio, re) in AMOUNT_LEAD_REGEXES) {
            for (m in re.findAll(text)) {
                val d = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: continue
                if (d <= 0.0) continue
                val cents = Math.round(d * 100)
                val better = prio > bestPrio || (prio == bestPrio && m.range.first < bestPos)
                if (better) {
                    bestPrio = prio
                    bestPos = m.range.first
                    bestCents = cents
                }
            }
        }
        if (bestCents != null) return bestCents

        // 阶段二~四：靠符号或单位推断，必须跳过余额/可用额度这类
        val skipWords = listOf("余额", "可用", "额度", "剩余", "总计有")
        for (regex in AMOUNT_REGEXES) {
            for (m in regex.findAll(text)) {
                val before = text.substring(maxOf(0, m.range.first - 12), m.range.first)
                if (skipWords.any { before.contains(it) }) continue
                val d = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: continue
                if (d > 0.0) return Math.round(d * 100)
            }
        }
        return null
    }

    /** 按商户名 + 通知文本猜分类 */
    fun guessCategory(app: String, merchant: String, text: String, type: TxType = TxType.EXPENSE): String {
        val s = "$merchant $text $app"

        if (type == TxType.INCOME) {
            // 判定顺序很重要：经营要在转账之前
            // 因为「老顾客向你转账货款」这种，本质是经营收入不是私人转账
            return when {
                // 退款必须排最前：通知里常同时出现「订单」「商品」，会被误判成经营
                listOf("退款", "退回", "退货", "原路退回", "退回余额").any { s.contains(it) } -> "退款"

                listOf("工资", "薪资", "代发", "薪酬").any { s.contains(it) } -> "工资"

                // ---- 经营：生意上的收款 ----
                listOf("货款", "订单收入", "销售额", "营业款", "营业",
                       "老顾客", "顾客", "客户", "店主", "店铺", "小店",
                       "商家", "收款码", "商户", "售出", "成交",
                       "订单收入", "商品收入").any { s.contains(it) } -> "经营"
                listOf("红包", "恭喜发财").any { s.contains(it) } -> "红包"

                // ---- 转账：私人往来 ----
                // 收款类默认按私人转账处理；若前面已被「商家/顾客/货款」命中，会先归经营
                listOf("转账", "收到转账", "转入", "已收款", "向你转账",
                       "收款", "AA", "还钱", "借款", "还款").any { s.contains(it) } -> "转账"

                listOf("理财", "基金", "利息", "收益", "分红").any { s.contains(it) } -> "理财"
                listOf("报销").any { s.contains(it) } -> "报销"
                else -> "其他"
            }
        }

        // 转账类优先判定，避免"转账给你吃饭"这种被误归餐饮
        if (listOf("转账", "转给你", "向你转账", "已收款", "收款成功", "还款", "提现").any { s.contains(it) } &&
            !listOf("美团", "饿了么", "闪送", "大众点评").any { s.contains(it) }) {
            return "转账"
        }

        // 先走打分制（能处理"某某餐饮管理公司买手机"这类冲突）
        CategoryRules.classifyExpense(s)?.let { return it }

        // 打分不明确时，回落到原来的关键词兜底
        return when {
            // 餐饮（先判，避免被"购物"抢走）
            listOf("美团外卖", "饿了么", "外卖", "餐厅", "饭店", "烧烤", "火锅", "咖啡",
                   "奶茶", "小吃", "早餐", "午餐", "晚餐", "肯德基", "麦当劳", "星巴克",
                   "瑞幸", "蜜雪", "汉堡", "米线", "面馆", "食堂", "点评").any { s.contains(it) } -> "餐饮"

            // 跑腿快递
            listOf("闪送", "跑腿", "快递", "物流", "配送", "顺丰", "菜鸟", "寄件").any { s.contains(it) } -> "跑腿快递"

            // 交通
            listOf("打车", "滴滴", "地铁", "公交", "加油", "停车", "高铁", "火车",
                   "机票", "曹操", "T3出行", "充电桩", "ETC").any { s.contains(it) } -> "交通"

            // 通讯
            listOf("话费", "流量", "宽带", "联通", "移动通信", "电信", "手机充值").any { s.contains(it) } -> "通讯"

            // 娱乐（含抖音打赏、直播间礼物）
            listOf("电影", "游戏", "会员", "视频", "音乐", "KTV", "影院", "票务",
                   "演出", "购票", "Steam", "网吧", "抖币", "打赏", "礼物",
                   "直播间", "充值抖币", "快币").any { s.contains(it) } -> "娱乐"

            // 医疗
            listOf("医院", "药房", "药店", "诊所", "挂号", "体检", "医疗", "口腔").any { s.contains(it) } -> "医疗"

            // 住房
            listOf("房租", "物业", "水费", "电费", "燃气", "租金", "中介费").any { s.contains(it) } -> "住房"

            // 日用
            listOf("超市", "便利店", "日用", "洗护", "生鲜", "菜市场", "粮油", "7-11", "全家").any { s.contains(it) } -> "日用"

            // 购物（含小红书、抖音商城、头条小店）
            listOf("淘宝", "天猫", "京东", "拼多多", "闲鱼", "商城", "购物", "专卖店",
                   "旗舰店", "小红书", "抖音商城", "抖音超市", "精选联盟",
                   "头条小店", "放心购", "橱窗", "带货").any { s.contains(it) } -> "购物"

            // 转账
            // ⚠️ 别把裸「余额」放进来：支付宝每笔付款页都写「付款方式 账户余额」，
            //    会把所有付款都误判成转账（线上实际踩过：71 元的餐馆消费被判成转账）。
            //    也别用裸「收款」：那是收入侧的词，支出侧出现只会是噪音。
            listOf("转账", "红包", "提现", "AA", "还款", "信用卡还款").any { s.contains(it) } -> "转账"

            else -> "其他"
        }
    }

}
