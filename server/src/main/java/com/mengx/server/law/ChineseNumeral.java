package com.mengx.server.law;

/**
 * 中文条号解析：「第三百一十一条」→ 311。
 * 法条编号检索（「第311条」「民法典311条」）需要把条号统一成数值。
 */
final class ChineseNumeral {

    private ChineseNumeral() {}

    private static final String DIGITS = "零一二三四五六七八九";
    private static final String UNITS = "十百千";

    /** 解析「第X条」中的中文数字；非中文数字（已是阿拉伯数字）直接解析 */
    static Integer parse(String articleNo) {
        if (articleNo == null) {
            return null;
        }
        String s = articleNo.replace("第", "").replace("条", "").trim();
        // 「第二百六十六条之一」：截掉「之…」后缀取主号
        int zh = s.indexOf('之');
        if (zh > 0) {
            s = s.substring(0, zh);
        }
        if (s.isEmpty()) {
            return null;
        }
        // 纯阿拉伯数字（第311条 / 第311条之一写法中的主号）
        if (s.chars().allMatch(Character::isDigit)) {
            return Integer.valueOf(s);
        }
        return parseChinese(s);
    }

    /** 中文数字（含「三十一」「一百零五」）转数值，无法解析返回 null */
    private static Integer parseChinese(String s) {
        long result = 0;
        long current = 0;
        boolean any = false;
        for (char c : s.toCharArray()) {
            int digit = DIGITS.indexOf(c);
            if (digit >= 0) {
                current = current == 0 ? digit : current * 10 + digit;
                if (digit != 0) {
                    any = true;
                }
                continue;
            }
            int unit = UNITS.indexOf(c);
            if (unit < 0) {
                return null; // 「之一」「之二」等后缀：主号已解析，返回 null 由调用方处理
            }
            long unitValue = (long) Math.pow(10, unit + 1);
            if (current == 0) {
                current = 1; // 「十」开头 = 1*10
            }
            result += current * unitValue;
            current = 0;
            any = true;
        }
        if (!any) {
            return null;
        }
        result += current;
        return result > Integer.MAX_VALUE ? null : (int) result;
    }
}
