package com.mengx.server.law;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mengx.server.agent.AgentRunner;
import com.mengx.server.llm.LlmUsage;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 判卷官（法学域内子能力）：主观题按采分点逐点 LLM 批改。
 * 输入题干+采分点+参考答案+作答 → 严格 JSON 判分（score/total/逐点 verdict）→
 * law_answer_records 落库（answer_text 全文、score 得分率 0~100、details 逐点判定、is_correct≥60 及格）。
 * 批改失败返回 null（调用方兜底提示，不阻断会话）。
 */
@Service
public class LawSubjectiveJudgeService {

    private static final Logger log = LoggerFactory.getLogger(LawSubjectiveJudgeService.class);

    /** 主观题及格口径：得分率 ≥60 视为通过（对齐法考主观题 108/180≈60%），落 is_correct 供画像聚合 */
    static final int PASS_RATE = 60;

    /** 作答超长截断（批改输入上限，防 prompt 膨胀） */
    private static final int MAX_ANSWER_CHARS = 5000;

    public record PointVerdict(String point, String verdict, String comment) {}

    /**
     * 批改结果：alreadyAnswered=此前已批（重交，携上次得分）；verdict 取值 hit/partial/miss。
     * referenceAnswer=参考答案要点（讲评尾部对照展示）。
     */
    public record GradeOutcome(boolean alreadyAnswered, Integer score, Integer total,
            List<PointVerdict> points, String summary, String missing, String referenceAnswer) {}

    // 批改模型输出 DTO（camelCase 与批改 prompt 对齐）
    private record GenPoint(String point, String verdict, String comment) {}
    private record GenGrade(Integer score, Integer total, List<GenPoint> points,
            String summary, String missing) {}

    private static final String GRADE_SYSTEM_PROMPT = """
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
            """;

    private final AgentRunner agentRunner;
    private final LawQuestionRepository questionRepository;
    private final LawAnswerRecordRepository answerRecordRepository;
    private final ObjectMapper objectMapper;

    public LawSubjectiveJudgeService(
            AgentRunner agentRunner,
            LawQuestionRepository questionRepository,
            LawAnswerRecordRepository answerRecordRepository,
            ObjectMapper objectMapper) {
        this.agentRunner = agentRunner;
        this.questionRepository = questionRepository;
        this.answerRecordRepository = answerRecordRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 批改一道主观题并落库：重交返回上次批改（alreadyAnswered=true）；
     * 题目缺失/作答为空/模型判分失败均返回 null，由调用方兜底。
     */
    public GradeOutcome grade(Long userId, Long sessionId, Long questionId, String userAnswer) {
        LawQuestionEntity q = questionRepository.findById(questionId).orElse(null);
        if (q == null) {
            log.warn("主观题批改：题目不在库（questionId={}）", questionId);
            return null;
        }
        if (userAnswer == null || userAnswer.isBlank()) {
            return null;
        }
        List<PointVerdict> defined = scoringPoints(q);
        if (defined.isEmpty()) {
            log.warn("主观题批改：题目无采分点数据（questionId={}）", questionId);
            return null;
        }
        int total = defined.stream().mapToInt(p -> pointScore(q, p.point())).sum();
        if (answerRecordRepository.existsByUserIdAndQuestionId(userId, questionId)) {
            return lastOutcome(userId, q, total);
        }
        String answer = userAnswer.length() > MAX_ANSWER_CHARS
                ? userAnswer.substring(0, MAX_ANSWER_CHARS) : userAnswer;
        String userPrompt = "【题目】\n%s\n\n【采分点（共 %d 分）】\n%s\n\n【参考答案】\n%s\n\n【作答】\n%s"
                .formatted(q.getStem(), total, joinPoints(defined, q),
                        q.getAnswer() == null ? "（无）" : q.getAnswer(), answer);
        long start = System.currentTimeMillis();
        LlmUsage.set("grade"); // 用量观测：判卷官场景
        GenGrade g;
        try {
            g = agentRunner.runForJson(GRADE_SYSTEM_PROMPT, userPrompt, GenGrade.class).orElse(null);
        } finally {
            LlmUsage.clear();
        }
        if (g == null || g.score() == null || g.points() == null || g.points().isEmpty()) {
            log.warn("判卷官输出无法识别（questionId={}，耗时 {}ms）", questionId,
                    System.currentTimeMillis() - start);
            return null;
        }
        int score = Math.max(0, Math.min(total, g.score()));
        List<PointVerdict> verdicts = new ArrayList<>();
        for (GenPoint p : g.points()) {
            if (p != null && p.point() != null && !p.point().isBlank()) {
                verdicts.add(new PointVerdict(p.point(), normalizeVerdict(p.verdict()),
                        p.comment() == null ? "" : p.comment()));
            }
        }
        int rate = total <= 0 ? 0 : score * 100 / total;
        LawAnswerRecordEntity rec = new LawAnswerRecordEntity(
                userId, sessionId, questionId, "", rate >= PASS_RATE);
        rec.setAnswerText(userAnswer);
        rec.setScore(rate);
        rec.setDetails(toJson(verdicts));
        answerRecordRepository.save(rec);
        log.info("主观题批改落库（questionId={}，{} 分/{} 分（得分率 {}），耗时 {}ms）",
                questionId, score, total, rate, System.currentTimeMillis() - start);
        return new GradeOutcome(false, score, total, verdicts,
                g.summary() == null ? "" : g.summary(),
                g.missing() == null ? "" : g.missing(),
                q.getAnswer() == null ? "" : q.getAnswer());
    }

    /** 重交：取上次批改记录拼结果（details 反序列化容错，坏数据退化为仅分数） */
    private GradeOutcome lastOutcome(Long userId, LawQuestionEntity q, int total) {
        LawAnswerRecordEntity last =
                answerRecordRepository.findFirstByUserIdAndQuestionIdOrderByIdDesc(userId, q.getId());
        if (last == null) {
            return null;
        }
        List<PointVerdict> points = new ArrayList<>();
        if (last.getDetails() != null && !last.getDetails().isBlank()) {
            try {
                points = objectMapper.readValue(last.getDetails(), objectMapper.getTypeFactory()
                        .constructCollectionType(List.class, PointVerdict.class));
            } catch (Exception e) {
                log.warn("上次批改 details 解析失败（退化仅分数）：{}", e.getMessage());
            }
        }
        return new GradeOutcome(true, last.getScore() == null ? null : last.getScore() * total / 100,
                total, points, "", "", q.getAnswer() == null ? "" : q.getAnswer());
    }

    /** 采分点解析（scoring_points jsonb → DTO；坏数据返回空） */
    private List<PointVerdict> scoringPoints(LawQuestionEntity q) {
        if (q.getScoringPoints() == null || q.getScoringPoints().isBlank()) {
            return List.of();
        }
        try {
            List<java.util.Map<String, Object>> raw = objectMapper.readValue(q.getScoringPoints(),
                    objectMapper.getTypeFactory()
                            .constructCollectionType(List.class, java.util.Map.class));
            List<PointVerdict> out = new ArrayList<>();
            for (java.util.Map<String, Object> m : raw) {
                Object point = m.get("point");
                if (point != null && !String.valueOf(point).isBlank()) {
                    out.add(new PointVerdict(String.valueOf(point), null, null));
                }
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 采分点分值查询（坏数据计 0 分，total 由调用方兜底） */
    private int pointScore(LawQuestionEntity q, String point) {
        try {
            List<java.util.Map<String, Object>> raw = objectMapper.readValue(q.getScoringPoints(),
                    objectMapper.getTypeFactory()
                            .constructCollectionType(List.class, java.util.Map.class));
            for (java.util.Map<String, Object> m : raw) {
                if (point != null && point.equals(m.get("point"))) {
                    Object score = m.get("score");
                    return score instanceof Number n ? n.intValue() : 0;
                }
            }
        } catch (Exception e) {
            log.warn("采分点分值解析失败：{}", e.getMessage());
        }
        return 0;
    }

    private String joinPoints(List<PointVerdict> points, LawQuestionEntity q) {
        StringBuilder sb = new StringBuilder();
        for (PointVerdict p : points) {
            sb.append("- ").append(p.point()).append("（").append(pointScore(q, p.point()))
                    .append(" 分）\n");
        }
        return sb.toString();
    }

    /** verdict 归一：hit/partial/miss 之外的值归 miss（讲评展示容错） */
    static String normalizeVerdict(String verdict) {
        return "hit".equals(verdict) || "partial".equals(verdict) ? verdict : "miss";
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "[]";
        }
    }
}
