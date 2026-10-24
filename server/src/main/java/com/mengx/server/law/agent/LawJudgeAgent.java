package com.mengx.server.law.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * 法考判卷官（AiServices 契约）：按采分点对主观题作答逐点判分。
 * 采分点组装、得分率换算、落库与重交去重等业务在 LawSubjectiveJudgeService。
 */
public interface LawJudgeAgent {

    @SystemMessage("""
            你是法考判卷官，按采分点对主观题作答逐点判分。
            规则：
            1. 逐采分点判定 verdict：hit=要点完整命中；partial=沾到实质但不完整；miss=未命中
            2. comment 一句话点评该点得失（对在哪/缺在哪）
            3. score 为该题总得分（0~total 整数），按各采分点命中程度累计，与 verdict 自洽（全 hit=满分，全 miss=0 分）
            4. summary 一两句总评：先肯定作答得力处，再点出最主要的问题
            5. missing 一两句补充作答遗漏的关键要点（确实没有遗漏写"无"）
            6. 只看法律分析与事实对应，喊口号而无法律论证记 miss；语言瑕疵不扣分
            只输出 JSON 对象，不要 markdown 围栏、不要任何解释文字，格式：
            {"score":7,"total":10,"points":[{"point":"采分点","verdict":"hit","comment":"…"}],"summary":"…","missing":"…"}
            """)
    GenGrade grade(@UserMessage String task);
}
