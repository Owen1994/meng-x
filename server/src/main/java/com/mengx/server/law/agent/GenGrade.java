package com.mengx.server.law.agent;

import java.util.List;

/** 判卷官输出：总分 + 逐采分点判定（hit/partial/miss）+ 总评与遗漏补充 */
public record GenGrade(
        Integer score,
        Integer total,
        List<GenPoint> points,
        String summary,
        String missing) {

    public record GenPoint(String point, String verdict, String comment) {}
}
