package com.qinkun.accountbook.util

/**
 * 通知原文脱敏。
 *
 * 为什么需要：银行短信、支付通知里常夹带手机号、银行卡号、身份证号。
 * 这些会跟着 rawText 一起存进本地数据库。手机丢了就等于全泄露，
 * 将来做导出/备份更是硬伤。
 *
 * 原则：只打码「显然属于个人身份信息」的长数字串，不动金额。
 * 金额带小数点（25.80），不会命中 13~19 位连续数字的规则。
 */
object PiiMasker {

    /** 身份证：18 位，末位可能是 X */
    private val ID_CARD = Regex(
        """(?<![0-9])[1-9][0-9]{5}(?:19|20)[0-9]{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12][0-9]|3[01])[0-9]{3}[0-9Xx](?![0-9])"""
    )

    /** 手机号：1 开头 11 位 */
    private val PHONE = Regex("""(?<![0-9])1[3-9][0-9]{9}(?![0-9])""")

    /** 银行卡（带空格/横线分组）：6222 0212 3456 7890 */
    private val BANK_CARD_GROUPED = Regex(
        """(?<![0-9])[0-9]{4}[ -][0-9]{4}[ -][0-9]{4}[ -][0-9]{4,7}(?![0-9])"""
    )

    /**
     * 银行卡：15~19 位连续数字。
     * 下限取 15 而不是 13，避免把快递单号、订单号误伤太狠；
     * 支付宝 20 位订单号不会被命中。
     */
    private val BANK_CARD = Regex("""(?<![0-9])[0-9]{15,19}(?![0-9])""")

    /** 原文入库上限，防止异常长文本撑爆数据库 */
    const val MAX_LEN = 800

    fun mask(text: String): String {
        if (text.isEmpty()) return text
        var s = text
        s = ID_CARD.replace(s, "[身份证]")
        s = PHONE.replace(s, "[手机号]")
        s = BANK_CARD_GROUPED.replace(s, "[卡号]")
        s = BANK_CARD.replace(s, "[卡号]")
        return if (s.length > MAX_LEN) s.take(MAX_LEN) + "…" else s
    }
}
