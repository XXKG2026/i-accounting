package com.qinkun.accountbook.service

import com.qinkun.accountbook.data.TxType

/**
 * 分类打分制 —— 替代原来「命中第一个关键词就定类」的做法。
 *
 * 为什么换：原来的写法有两个硬伤
 *   1. 「某某餐饮管理有限公司」买手机，会因为含"餐饮"被算成餐饮
 *   2. 「小米」既可能是手机（数码）也可能是小米粥（餐饮），全看词表顺序，极不稳定
 *
 * 现在的做法：
 *   每个分类有一组带权重的词，命中就累加，最后分数最高的胜出；
 *   另外每个分类可以配「排除词」，命中即该分类直接出局（不是扣分，是淘汰）。
 *
 * 权重分档（越具体越高）：
 *   强信号 10 —— 餐厅、地铁、物业费这种一看就知道的
 *   品牌    8 —— 麦当劳、瑞幸、山姆
 *   品类    6 —— 外卖、奶茶、便利店
 *   后缀    3 —— 小馆、食府
 *
 * 最高分低于 3（也就是只碰到后缀词）时返回 null，交回给原来的兜底逻辑，
 * 避免"猜得太随意"。
 */
object CategoryRules {

    private enum class Tier(val weight: Int) { STRONG(10), BRAND(8), CATEGORY(6), SUFFIX(3), EXCLUDE(0) }

    private data class Rule(val words: List<String>, val tier: Tier, val category: String)

    /** 支出侧规则表 */
    private val EXPENSE_RULES: List<Rule> = listOf(
        // ---------------- 餐饮 ----------------
        Rule(listOf("餐厅", "饭店", "饭馆", "酒楼", "食堂", "火锅", "烧烤", "小吃", "米线",
                    "面馆", "快餐", "早餐", "午餐", "晚餐", "夜宵", "咖啡", "奶茶", "茶饮",
                    "烘焙", "蛋糕", "甜品"), Tier.STRONG, "餐饮"),
        Rule(listOf("美团外卖", "饿了么", "肯德基", "麦当劳", "星巴克", "瑞幸", "蜜雪冰城",
                    "茶百道", "喜茶", "奈雪", "古茗", "霸王茶姬", "沪上阿姨", "海底捞",
                    "西贝", "外婆家", "汉堡王", "必胜客", "塔斯汀", "华莱士", "老乡鸡",
                    "吉野家", "真功夫", "南城香", "和府捞面", "瑞幸咖啡"), Tier.BRAND, "餐饮"),
        Rule(listOf("外卖", "餐饮", "美食", "饭店"), Tier.CATEGORY, "餐饮"),
        Rule(listOf("小馆", "食府", "菜馆", "面店"), Tier.SUFFIX, "餐饮"),
        Rule(listOf("单车", "打车", "超市", "商城"), Tier.EXCLUDE, "餐饮"),

        // ---------------- 购物 ----------------
        Rule(listOf("旗舰店", "专卖店", "购物", "百货", "商场"), Tier.STRONG, "购物"),
        Rule(listOf("淘宝", "天猫", "京东", "拼多多", "唯品会", "闲鱼", "苏宁", "国美",
                    "山姆", "沃尔玛", "优衣库", "耐克", "阿迪达斯", "小红书", "抖音商城",
                    "头条小店", "精选联盟", "1688"), Tier.BRAND, "购物"),
        Rule(listOf("商城", "购买", "下单", "订单", "商品", "店铺"), Tier.CATEGORY, "购物"),
        Rule(listOf("外卖", "餐费", "餐饮", "地铁", "打车"), Tier.EXCLUDE, "购物"),

        // ---------------- 交通 ----------------
        Rule(listOf("打车", "网约车", "地铁", "公交", "加油", "停车", "高铁", "火车",
                    "机票", "车费", "过路费", "充电桩"), Tier.STRONG, "交通"),
        Rule(listOf("滴滴", "高德打车", "T3出行", "曹操出行", "首汽", "哈啰", "青桔",
                    "美团单车", "12306", "南航", "国航", "东航", "海航", "中石化", "中石油"), Tier.BRAND, "交通"),
        Rule(listOf("出行", "路费", "交通"), Tier.CATEGORY, "交通"),

        // ---------------- 跑腿快递 ----------------
        Rule(listOf("跑腿", "快递", "寄件", "物流", "运费"), Tier.STRONG, "跑腿快递"),
        Rule(listOf("闪送", "顺丰", "菜鸟", "中通", "圆通", "韵达", "申通", "极兔",
                    "达达", "UU跑腿", "京东物流"), Tier.BRAND, "跑腿快递"),

        // ---------------- 通讯 ----------------
        Rule(listOf("话费", "流量", "宽带", "手机充值", "网费"), Tier.STRONG, "通讯"),
        Rule(listOf("中国移动", "中国联通", "中国电信", "移动通信"), Tier.BRAND, "通讯"),

        // ---------------- 娱乐 ----------------
        Rule(listOf("电影", "影院", "游戏", "网吧", "演出", "门票", "票务", "KTV", "健身"), Tier.STRONG, "娱乐"),
        Rule(listOf("腾讯视频", "爱奇艺", "优酷", "B站", "bilibili", "网易云音乐", "QQ音乐",
                    "Steam", "Switch", "PSN", "抖音", "快手", "咪咕"), Tier.BRAND, "娱乐"),
        Rule(listOf("会员", "充值", "抖币", "快币", "打赏", "礼物", "直播"), Tier.CATEGORY, "娱乐"),

        // ---------------- 医疗 ----------------
        Rule(listOf("医院", "药店", "药房", "诊所", "挂号", "体检", "口腔", "牙科", "眼科", "医疗"), Tier.STRONG, "医疗"),
        Rule(listOf("老百姓大药房", "益丰", "大参林", "同仁堂", "海王星辰"), Tier.BRAND, "医疗"),

        // ---------------- 住房 ----------------
        Rule(listOf("房租", "租金", "物业费", "水费", "电费", "燃气费", "供暖", "中介费", "取暖费"), Tier.STRONG, "住房"),
        Rule(listOf("物业", "水电", "燃气"), Tier.CATEGORY, "住房"),

        // ---------------- 日用 ----------------
        Rule(listOf("超市", "便利店", "菜市场", "生鲜", "粮油", "日用", "洗护", "水果"), Tier.STRONG, "日用"),
        Rule(listOf("7-11", "7-Eleven", "全家", "罗森", "便利蜂", "美宜佳", "永辉",
                    "盒马", "叮咚买菜", "美团买菜", "大润发", "华润万家"), Tier.BRAND, "日用"),

        // ---------------- 教育 ----------------
        Rule(listOf("学费", "培训", "课程", "网课", "教材", "补习", "考试"), Tier.STRONG, "教育"),
        Rule(listOf("得到", "极客时间", "学而思", "新东方", "猿辅导", "作业帮"), Tier.BRAND, "教育"),

        // ---------------- 人情 ----------------
        Rule(listOf("随礼", "礼金", "份子钱", "送礼", "红包"), Tier.STRONG, "人情"),

        // ---------------- 宠物 ----------------
        Rule(listOf("宠物", "猫粮", "狗粮", "宠物医院", "驱虫", "疫苗", "猫砂"), Tier.STRONG, "宠物"),

        // ---------------- 数码 ----------------
        Rule(listOf("手机", "电脑", "耳机", "平板", "相机", "显卡", "显示器", "键盘", "硬盘"), Tier.STRONG, "数码"),
        Rule(listOf("苹果", "Apple", "华为", "小米", "OPPO", "vivo", "荣耀", "联想",
                    "戴尔", "索尼", "大疆", "罗技"), Tier.BRAND, "数码"),
        Rule(listOf("小米粥", "苹果汁", "苹果醋"), Tier.EXCLUDE, "数码"),

        // ---------------- 转账 ----------------
        Rule(listOf("转账", "红包", "AA", "提现", "收款", "还款", "信用卡还款"), Tier.STRONG, "转账"),
    )

    /**
     * 支出分类。返回 null 表示"打分不明确"，交给调用方的兜底逻辑处理。
     */
    fun classifyExpense(text: String): String? {
        // 每个分类取「命中词里的最高权重」，而不是把所有词相加 ——
        // 词表长的分类不该因为词多就占便宜。
        val bestWeight = LinkedHashMap<String, Int>()
        val bestLen = HashMap<String, Int>()      // 同权重时，命中词更长的更具体
        val killed = HashSet<String>()            // 被排除词否决的分类

        for (rule in EXPENSE_RULES) {
            for (word in rule.words) {
                if (!text.contains(word)) continue
                if (rule.tier == Tier.EXCLUDE) {
                    killed.add(rule.category)
                    continue
                }
                if (rule.category in killed) continue
                val w = rule.tier.weight
                if (w > (bestWeight[rule.category] ?: 0)) {
                    bestWeight[rule.category] = w
                    bestLen[rule.category] = word.length
                } else if (w == (bestWeight[rule.category] ?: 0)) {
                    bestLen[rule.category] = maxOf(bestLen[rule.category] ?: 0, word.length)
                }
            }
        }

        // 被排除词否决的分类，即使前面已累计分数也要清掉
        killed.forEach { bestWeight.remove(it); bestLen.remove(it) }

        // 逐个比较，保证结果稳定（不依赖 HashMap 遍历顺序）
        var bestCat: String? = null
        var bestScore = -1
        var bestWordLen = -1
        for ((cat, w) in bestWeight) {
            val len = bestLen[cat] ?: 0
            val better = w > bestScore || (w == bestScore && len > bestWordLen)
            if (better) {
                bestCat = cat; bestScore = w; bestWordLen = len
            }
        }

        // 只命中「后缀词」（3 分）说明证据太弱，交回兜底逻辑
        if (bestCat == null || bestScore <= Tier.SUFFIX.weight) return null
        return bestCat
    }

    /** 统一入口：目前只对支出启用打分，收入继续走原有规则（已验证稳定） */
    fun classify(text: String, type: TxType): String? =
        if (type == TxType.EXPENSE) classifyExpense(text) else null
}
