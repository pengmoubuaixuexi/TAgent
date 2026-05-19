package cn.bugstack.ai.domain.agent.service.security;

import java.util.regex.Pattern;

/**
 * P2.5 14.2 PII 脱敏：对敏感字段做掩码。
 * <p>
 * 覆盖：手机号 / 身份证号 / 邮箱 / 银行卡号 / IP 地址 / 微信号。
 * 不阻断请求，只做掩码替换——确保即使 prompt/日志/前端泄露，明文敏感信息也已脱敏。
 */
public class PiiMasker {

    // 中国手机号：1[3-9]\d{9}（前后必须非数字，防止吃掉身份证 / 银行卡里的子串）
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(1[3-9]\\d)\\d{4}(\\d{4})(?!\\d)");
    // 身份证：18位或15位
    private static final Pattern ID_CARD = Pattern.compile("(\\d{6})(\\d{8}|\\d{10})(\\d{2}[0-9Xx]?)");
    // 微信号
    private static final Pattern WECHAT_ID = Pattern.compile("(wxid_)[a-zA-Z0-9]+");
    // 邮箱
    private static final Pattern EMAIL = Pattern.compile("([\\w.+-]{1,3})[\\w.+-]*@([\\w]+)\\.[\\w.]+");
    // 银行卡：16-19位数字
    private static final Pattern BANK_CARD = Pattern.compile("(?<!\\d)(\\d{4})\\d{8,11}(\\d{4})(?!\\d)");
    // IPv4
    private static final Pattern IPV4 = Pattern.compile("(\\d{1,3})\\.\\d{1,3}\\.\\d{1,3}\\.(\\d{1,3})");

    /**
     * 对文本中的 PII 做掩码，返回脱敏后的字符串（半角 *）。null/blank 穿通。
     */
    public static String mask(String text) {
        if (text == null || text.isBlank()) return text;
        String s = text;
        s = PHONE.matcher(s).replaceAll("$1****$2");
        s = ID_CARD.matcher(s).replaceAll("$1********$3");
        s = EMAIL.matcher(s).replaceAll("$1***@$2.***");
        s = BANK_CARD.matcher(s).replaceAll("$1********$2");
        s = IPV4.matcher(s).replaceAll("$1.*.*.$2");
        s = WECHAT_ID.matcher(s).replaceAll("$1***");
        return s;
    }

    private PiiMasker() {}
}
