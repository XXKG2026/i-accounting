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
    )

    /** 支出关键词 */
    private val EXPENSE_WORDS = listOf(
        "支付成功", "付款成功", "已付款", "成功付款", "支付完成",
        "消费", "扣款", "已支付", "订单支付", "扣费", "支出",
        // 「实付」是支付宝/美团最高频的金额引导词，必须认
        "实付", "已实付", "付款金额", "支付金额", "本次消费", "扣款金额"
    )

    /** 收入关键词 */
    private val INCOME_WORDS = listOf(
        "收款成功", "已收款", "到账", "退款成功", "已退款", "收入", "转入",
        // 微信/支付宝转账的高频说法
        "收到转账", "成功收款", "你已成功收款", "向你转账", "已到账"
    )

    /** 排除词：命中就不记账（避免误记） */
    private val EXCLUDE_WORDS = listOf(
        // 这些是"根本不该记账"的通知类型，命中即丢
        "余额提醒", "账单提醒", "还款提醒",
        "待支付", "请支付", "立即支付",
        "会员到期", "签到", "抽奖", "为你推荐", "猜你喜欢",
    )

    /**
     * 成交确认词 —— 出现这些，说明这是一笔**真实交易**，
     * 此时即使文案里带"优惠券""立减"，也不能当广告丢掉。
     * （例如「使用优惠券后实付 20.00 元」是真交易）
     */
    private val DEAL_CONFIRM = listOf(
        "支付成功", "付款成功", "已支付", "已付款", "支付完成", "付款完成",
        "交易成功", "实付", "扣款", "已扣款",
        // 「消费」本身是真交易的说法（广告一般只说"支出"，不说"消费 XX 元"）
        // 少了它，「消费 20 元，使用优惠券」这种真交易会被误杀
        "消费",
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
     * 金额正则，严格按语义优先级排列。
     * 第一优先：实付/支付/付款/收款/到账/金额 这类词后面的数字（最可信）
     * 第二优先：带 ¥ 的
     * 第三优先：X.XX 元
     * 绝不单独抓裸的 12.34（会把订单号、比例、时间当金额）
     */
    private val AMOUNT_REGEXES = listOf(
        // 阶段一：明确的"金额引导词"，命中即可信，不做余额过滤
        Regex("""(?:实付|实收|实际支付|付款金额|支付金额|收款金额|本次消费|消费金额|共计|合计|到账金额|扣款金额)[^0-9￥¥]{0,10}[¥￥]?\s*([0-9,]+(?:\.[0-9]{1,2})?)"""),
        // 阶段二：带货币符号的
        Regex("""(?<![0-9.,])[¥￥]\s*([0-9,]+(?:\.[0-9]{1,2})?)"""),
        // 阶段三：X.XX 元
        Regex("""(?<![0-9.])([0-9,]+\.[0-9]{2})\s*元"""),
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

        // 排除
        val hitExclude = EXCLUDE_WORDS.firstOrNull { blob.contains(it) }
        if (hitExclude != null) { onFail("命中排除词「$hitExclude」"); return null }

        val hasExpense = EXPENSE_WORDS.any { blob.contains(it) }
        val hasIncome = INCOME_WORDS.any { blob.contains(it) }
        if (!hasExpense && !hasIncome) { onFail("没找到支付/收款关键词"); return null }

        val cents = extractCents(blob)
        if (cents == null) { onFail("没找到金额"); return null }
        if (cents <= 0L || cents > 100_000_000L) { onFail("金额异常（$cents 分）"); return null }

        // 同时命中时以收入为准（"退款成功" 通常也含"支付"）
        val type = if (hasIncome) TxType.INCOME else TxType.EXPENSE

        // 广告通知过滤：像广告、又没有成交确认 → 不记账
        // 收入侧只过滤「红包奖励」这类纯营销文案，其余不动（红包入账是真钱）
        if (type == TxType.EXPENSE) {
            val adWord = MARKETING_MARKERS.firstOrNull { blob.contains(it) }
            val confirmed = DEAL_CONFIRM.any { blob.contains(it) }
            if (adWord != null && !confirmed) { onFail("疑似广告（含「$adWord」且无成交确认词）"); return null }
        } else {
            if (listOf("红包奖励", "奖励金", "返利券", "优惠券").any { blob.contains(it) } &&
                !listOf("红包到账", "红包收入", "转入", "收到红包").any { blob.contains(it) }
            ) { onFail("疑似营销奖励文案"); return null }
        }

        // 商户名：取标题，去掉 App 名后缀
        val merchant = title
            .replace(Regex("^【[^】]*】"), "")
            .replace(app, "")
            .replace(Regex("^[\\s:：\\-—|]+"), "")
            .replace(Regex("[\\s:：\\-—|]+$"), "")
            .take(40)
            .ifBlank { app }

        return Parsed(cents, type, app, merchant, blob)
    }

    /** 从文本里抓金额，返回「分」 */
    fun extractCents(text: String): Long? {
        // 阶段一：语义引导词命中的数字最可信，直接用
        for (m in AMOUNT_REGEXES[0].findAll(text)) {
            val d = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: continue
            if (d > 0.0) return Math.round(d * 100)
        }

        // 阶段二~四：靠符号或单位推断，必须跳过余额/可用额度这类
        val skipWords = listOf("余额", "可用", "额度", "剩余", "总计有")
        for (i in 1 until AMOUNT_REGEXES.size) {
            for (m in AMOUNT_REGEXES[i].findAll(text)) {
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
        if (listOf("转账", "转给你", "向你转账", "已收款", "收款成功").any { s.contains(it) } &&
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
            listOf("转账", "红包", "收款", "余额", "提现", "AA").any { s.contains(it) } -> "转账"

            else -> "其他"
        }
    }

}
