package com.mengx.server.law;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mengx.server.law.agent.GenOutput;
import com.mengx.server.law.agent.GenQuestion;
import com.mengx.server.law.agent.GenSubjective;
import com.mengx.server.law.agent.LawAgentFactory;
import com.mengx.server.llm.LlmUsage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 出题官（法学域内子能力，不进全局路由，由法学通道内的出题意图触发）：
 * 法条检索上下文 → glm-4.7 严格 JSON 出题 → law_questions 落库（source=generated，幂等 + 向量化）。
 * 判分：客观题字母比对 + law_answer_records 记录，纯代码零模型调用，即时反馈。
 * LLM 契约（出题 prompt/JSON 绑定）在 com.mengx.server.law.agent.LawQuestionGenAgent。
 */
@Service
public class LawQuestionGenService {

    private static final Logger log = LoggerFactory.getLogger(LawQuestionGenService.class);

    /** 一组题量（逐题生成，调用方按此数循环） */
    public static final int QUESTION_COUNT = 3;

    private final LawAgentFactory agents;
    private final LawQuestionRepository questionRepository;
    private final LawAnswerRecordRepository answerRecordRepository;
    private final LawEmbeddingClient embeddingClient;
    private final ObjectMapper objectMapper;

    public LawQuestionGenService(
            LawAgentFactory agents,
            LawQuestionRepository questionRepository,
            LawAnswerRecordRepository answerRecordRepository,
            LawEmbeddingClient embeddingClient,
            ObjectMapper objectMapper) {
        this.agents = agents;
        this.questionRepository = questionRepository;
        this.answerRecordRepository = answerRecordRepository;
        this.embeddingClient = embeddingClient;
        this.objectMapper = objectMapper;
    }

    // ---------- DTO（SSE question 事件 / idea_messages 信封 / 前端题目卡同构） ----------

    /** 题目 DTO：下发、信封、恢复共用；no=组内序号（第几题，交卷作答按此定位）；type=单选/多选/不定项 */
    public record QuizOption(String key, String text) {}

    public record QuizQuestion(Long id, Integer no, String stem, List<QuizOption> options,
                               String type, Integer difficulty, List<String> knowledgePoints) {}

    /** 题组信封：落 idea_messages（role=question），前端恢复时拆成逐题气泡 */
    public record QuizEnvelope(String topic, List<QuizQuestion> questions) {}

    /** 判分结果 */
    public record JudgeOutcome(boolean correct, boolean alreadyAnswered, String expected, String analysis) {}

    /**
     * 单题生成（逐题调用逐题下发，缩短首题等待）：检索上下文作依据 + 相近真题作风格参照
     * + 学情参考（弱点加权/已稳降频，空串不注入）→ 生成 1 题 → 幂等落库（含向量化）→ 题目 DTO；
     * 返回 null = 本题生成失败（调用方跳过）。
     *
     * @param no         组内序号（第几题，写入 DTO）
     * @param type       题型（单选/多选/不定项，指定本题出什么题型）
     * @param usedPoints 本组已出题目的考点（prompt 排除，防同组重复）
     */
    public QuizQuestion generateOne(String topic, List<LawSearchService.SearchHit> refs,
            List<LawQuestionEntity> examRefs, int no, String type,
            List<String> usedPoints, String profileBriefing) {
        String ragContext = LawSearchService.toContext(refs);
        String subjectHint = refs.isEmpty() ? null : refs.get(0).article().getLawCode();
        String used = usedPoints == null || usedPoints.isEmpty() ? ""
                : "\n【本组已出考点】：" + String.join("、", usedPoints);
        String brief = profileBriefing == null || profileBriefing.isBlank() ? ""
                : "\n【学情参考】" + profileBriefing;
        String userPrompt = "本次出 1 道【%s】题。主题：%s\n\n%s%s%s%s"
                .formatted(type == null || type.isBlank() ? "单选" : type,
                        topic == null || topic.isBlank() ? "（未指明，请依据下面的法条检索上下文自选高频考点）" : topic,
                        ragContext.isBlank() ? "（本轮无法条检索结果，凭所学出题）" : ragContext,
                        toExamRefContext(examRefs), used, brief);
        long start = System.currentTimeMillis();
        LlmUsage.set("quiz_gen"); // 用量观测：出题官场景标记（仅包裹模型调用段，检索/落库不占）
        GenOutput out;
        try {
            out = agents.questionGen().generateObjective(userPrompt);
        } catch (Exception e) {
            log.warn("出题官客观题模型调用失败（topic={}，第 {} 题）：{}", topic, no, e.getMessage());
            out = null;
        } finally {
            LlmUsage.clear();
        }
        if (out == null || out.questions() == null || out.questions().isEmpty()) {
            log.warn("出题官单题生成失败（topic={}，第 {} 题，耗时 {}ms）",
                    topic, no, System.currentTimeMillis() - start);
            return null;
        }
        GenQuestion picked = null;
        for (GenQuestion q : out.questions()) {
            if (q.stem() != null && !q.stem().isBlank() && q.options() != null
                    && q.options().size() == 4 && isValidAnswer(q.answer(), type)) {
                picked = q;
                break;
            }
        }
        if (picked == null) {
            log.info("出题官单题字段不全（topic={}，第 {} 题，{}）", topic, no, type);
            return null;
        }
        LawQuestionEntity e = upsert(picked, subjectHint, type);
        if (e == null) {
            return null;
        }
        vectorize(List.of(e));
        log.info("出题官单题收口（topic={}，第 {} 题（{}），耗时 {}ms）",
                topic, no, type, System.currentTimeMillis() - start);
        return fromEntity(e, no);
    }

    /**
     * 主观题（案例分析）生成：独立成组一次一道。检索上下文作依据 + 相近真题作风格参照
     * + 学情参考 → 生成 1 题含采分点 → 幂等落库（含向量化）→ 题目 DTO（type=主观，options 为空）；
     * 返回 null = 生成失败。采分点/参考答案不下发前端（防泄题），批改时从库中读取。
     */
    public QuizQuestion generateSubjective(String topic, List<LawSearchService.SearchHit> refs,
            List<LawQuestionEntity> examRefs, int no, List<String> usedPoints, String profileBriefing) {
        String ragContext = LawSearchService.toContext(refs);
        String subjectHint = refs.isEmpty() ? null : refs.get(0).article().getLawCode();
        String used = usedPoints == null || usedPoints.isEmpty() ? ""
                : "\n【已出考点】（避开这些，出新考点）：" + String.join("、", usedPoints);
        String brief = profileBriefing == null || profileBriefing.isBlank() ? ""
                : "\n【学情参考】" + profileBriefing;
        String userPrompt = "主题：%s\n\n%s%s%s%s"
                .formatted(topic == null || topic.isBlank() ? "（未指明，请依据下面的法条检索上下文自选高频考点）" : topic,
                        ragContext.isBlank() ? "（本轮无法条检索结果，凭所学出题）" : ragContext,
                        toExamRefContext(examRefs), used, brief);
        long start = System.currentTimeMillis();
        LlmUsage.set("quiz_gen"); // 用量观测：与客观出题同场景标记
        GenSubjective q;
        try {
            q = agents.questionGen().generateSubjective(userPrompt);
        } catch (Exception e) {
            log.warn("出题官主观题模型调用失败（topic={}）：{}", topic, e.getMessage());
            q = null;
        } finally {
            LlmUsage.clear();
        }
        if (!validSubjective(q)) {
            log.warn("出题官主观题生成失败（topic={}，耗时 {}ms）", topic, System.currentTimeMillis() - start);
            return null;
        }
        LawQuestionEntity e = upsertSubjective(q, subjectHint);
        if (e == null) {
            return null;
        }
        vectorize(List.of(e));
        log.info("出题官主观题收口（topic={}，第 {} 题，耗时 {}ms）",
                topic, no, System.currentTimeMillis() - start);
        return fromEntity(e, no);
    }

    /** 主观题生成结果校验：题干非空 + 采分点至少 2 个且要点文本均非空 */
    private static boolean validSubjective(GenSubjective q) {
        if (q == null || q.stem() == null || q.stem().isBlank()
                || q.scoringPoints() == null || q.scoringPoints().size() < 2) {
            return false;
        }
        return q.scoringPoints().stream()
                .allMatch(p -> p != null && p.point() != null && !p.point().isBlank());
    }

    /** 主观题幂等落库：options 固定 "[]"，answer=参考答案，采分点走 setScoringPoints */
    private LawQuestionEntity upsertSubjective(GenSubjective q, String subjectHint) {
        try {
            String pointsJson = objectMapper.writeValueAsString(q.scoringPoints());
            String hash = sha256(q.stem() + "|[]");
            List<LawQuestionEntity> existed = questionRepository.findByContentHashIn(List.of(hash));
            if (!existed.isEmpty()) {
                return existed.get(0);
            }
            LawQuestionEntity e = new LawQuestionEntity(
                    LawQuestionEntity.SOURCE_GENERATED, null,
                    subjectOf(subjectHint), "主观",
                    q.difficulty() == null ? 3 : Math.max(1, Math.min(5, q.difficulty())),
                    q.knowledgePoints() == null ? new String[0] : q.knowledgePoints().toArray(new String[0]),
                    q.stem(), "[]", q.answer() == null ? "" : q.answer(),
                    q.analysis() == null ? "" : q.analysis(), hash);
            e.setScoringPoints(pointsJson);
            return questionRepository.save(e);
        } catch (Exception ex) {
            log.warn("主观题落库失败（跳过该题）：{}", ex.getMessage());
            return null;
        }
    }

    /** 答案与题型匹配：单选恰 1 个字母；多选/不定项 2~4 个字母（字母去重排序后计长） */
    static boolean isValidAnswer(String answer, String type) {
        String letters = optionLetters(answer);
        if (letters == null) {
            return false;
        }
        return "单选".equals(type) ? letters.length() == 1 : letters.length() >= 2 && letters.length() <= 4;
    }

    /** 真题参照段（题干+选项+答案）：引导出题官揣摩风格与干扰项套路，禁止换皮原题 */
    private static String toExamRefContext(List<LawQuestionEntity> questions) {
        if (questions == null || questions.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(
                "\n【历年真题参照】（揣摩其题干风格与干扰项设计套路，出新题；禁止复述或换皮原题）\n");
        for (int i = 0; i < questions.size(); i++) {
            LawQuestionEntity q = questions.get(i);
            sb.append(i + 1).append(".（").append(q.getYear() == null ? "真题" : q.getYear() + "年真题")
                    .append("）").append(q.getStem() == null ? "" : q.getStem()).append('\n');
            if (q.getOptions() != null && !q.getOptions().isBlank()) {
                sb.append("  ").append(q.getOptions()).append('\n');
            }
            if (q.getAnswer() != null && !q.getAnswer().isBlank()) {
                sb.append("  [答案] ").append(q.getAnswer()).append('\n');
            }
        }
        return sb.toString();
    }

    /** 幂等落库：content_hash 命中复用已有题，未命中新插（subject 从检索命中的法名推导；type 用调用方指定值，answer 归一化多字母） */
    private LawQuestionEntity upsert(GenQuestion q, String subjectHint, String type) {
        try {
            String optionsJson = objectMapper.writeValueAsString(q.options());
            String hash = sha256(q.stem() + "|" + optionsJson);
            List<LawQuestionEntity> existed = questionRepository.findByContentHashIn(List.of(hash));
            if (!existed.isEmpty()) {
                return existed.get(0);
            }
            LawQuestionEntity e = new LawQuestionEntity(
                    LawQuestionEntity.SOURCE_GENERATED, null,
                    subjectOf(subjectHint), type == null || type.isBlank() ? "单选" : type,
                    q.difficulty() == null ? 3 : Math.max(1, Math.min(5, q.difficulty())),
                    q.knowledgePoints() == null ? new String[0] : q.knowledgePoints().toArray(new String[0]),
                    q.stem(), optionsJson,
                    optionLetters(q.answer()), q.analysis() == null ? "" : q.analysis(),
                    hash);
            return questionRepository.save(e);
        } catch (Exception ex) {
            log.warn("题目落库失败（跳过该题）：{}", ex.getMessage());
            return null;
        }
    }

    /** 批量向量化（题干+选项 json 拼接，一题一个向量块）；失败整体降级，不阻断出题 */
    private void vectorize(List<LawQuestionEntity> saved) {
        if (saved.isEmpty()) {
            return;
        }
        try {
            List<float[]> vectors = embeddingClient.embed(saved.stream()
                    .map(e -> e.getStem() + "\n" + e.getOptions())
                    .toList());
            for (int i = 0; i < saved.size() && i < vectors.size(); i++) {
                saved.get(i).setEmbedding(vectors.get(i));
            }
            questionRepository.saveAll(saved);
        } catch (Exception ex) {
            log.warn("题目向量化降级（不影响答题）：{}", ex.getMessage());
        }
    }

    /** 判分并落答题记录（多选/不定项全对才算对，答案归一化排序去重后比对；已答过不重复记） */
    public JudgeOutcome judge(Long userId, Long sessionId, Long questionId, String userAnswer) {
        LawQuestionEntity q = questionRepository.findById(questionId).orElse(null);
        if (q == null) {
            return new JudgeOutcome(false, true, "", "此题已不在题库，唤一声「继续出」重新出题便是。");
        }
        String expected = optionLetters(q.getAnswer());
        if (expected == null) {
            return new JudgeOutcome(false, false, "", "此题答案数据异常，跳过即可。");
        }
        if (answerRecordRepository.existsByUserIdAndQuestionId(userId, questionId)) {
            return new JudgeOutcome(false, true, expected, safeAnalysis(q));
        }
        String normalized = optionLetters(userAnswer);
        boolean correct = expected.equals(normalized);
        answerRecordRepository.save(new LawAnswerRecordEntity(userId, sessionId, questionId,
                normalized == null ? "" : normalized, correct));
        log.info("答题判分（questionId={}（{}），答 {}，正确答案 {}，{}）",
                questionId, q.getQuestionType(), normalized, expected, correct ? "对" : "错");
        return new JudgeOutcome(correct, false, expected, safeAnalysis(q));
    }

    /** 信封解析（idea_messages role=question → 题组）；失败返回 null */
    public QuizEnvelope parseEnvelope(String json) {
        try {
            return objectMapper.readValue(json, QuizEnvelope.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** 信封序列化（落库用） */
    public String envelopeJson(QuizEnvelope envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** 实体 → 题目 DTO（options jsonb 解析容错；no=组内序号由调用方按位次填） */
    private QuizQuestion fromEntity(LawQuestionEntity e, int no) {
        List<QuizOption> options = new ArrayList<>();
        try {
            options = objectMapper.readValue(e.getOptions(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, QuizOption.class));
        } catch (Exception ex) {
            log.warn("题目 options 解析失败（id={}）：{}", e.getId(), ex.getMessage());
        }
        return new QuizQuestion(e.getId(), no, e.getStem(), options,
                e.getQuestionType() == null ? "单选" : e.getQuestionType(), e.getDifficulty(),
                e.getKnowledgePoints() == null ? List.of() : List.of(e.getKnowledgePoints()));
    }

    /** 提取全部 A-D 选项字母（容错"答案是B""A、B、D"），去重升序拼接（"DBA"→"ABD"）；无字母返回 null */
    static String optionLetters(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        java.util.TreeSet<Character> letters = new java.util.TreeSet<>();
        for (char c : s.toUpperCase().toCharArray()) {
            if (c >= 'A' && c <= 'D') {
                letters.add(c);
            }
        }
        return letters.isEmpty() ? null
                : letters.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining());
    }

    /** law_code → 法考科目（粗分类，检索无命中时归综合） */
    private static String subjectOf(String lawCode) {
        if (lawCode == null || lawCode.isBlank()) {
            return "综合";
        }
        if (lawCode.contains("诉")) {
            return lawCode.contains("刑") ? "刑诉" : "民诉";
        }
        if (lawCode.contains("刑")) {
            return "刑法";
        }
        if (lawCode.contains("担保") || lawCode.contains("合同") || lawCode.contains("时间效力")
                || lawCode.contains("民")) {
            return "民法";
        }
        if (lawCode.contains("行政")) {
            return "行政法";
        }
        if (lawCode.contains("公司") || lawCode.contains("合伙") || lawCode.contains("破产")
                || lawCode.contains("票据") || lawCode.contains("证券") || lawCode.contains("保险")) {
            return "商经";
        }
        if (lawCode.contains("宪法") || lawCode.contains("立法")) {
            return "理论法";
        }
        return "综合";
    }

    private static String safeAnalysis(LawQuestionEntity q) {
        return q.getAnalysis() == null || q.getAnalysis().isBlank()
                ? "此题暂无解析，可追问考点。" : q.getAnalysis();
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            // 摘要算法必然存在，兜底取时间哈希
            return Long.toHexString(s.hashCode()) + Long.toHexString(System.nanoTime());
        }
    }
}
