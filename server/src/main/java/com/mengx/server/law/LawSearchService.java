package com.mengx.server.law;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 法条三路融合检索（RAG 召回层）：
 * ① 编号正则：「民法典第311条」→ (law_code, article_no_int) 精确命中，最高优先
 * ② 向量语义：千问 embedding 余弦近邻（语义改写/口语描述场景）
 * ③ trgm 词汇：pg_trgm 相似度（关键词式短查询）
 * ②③ 按 RRF（Reciprocal Rank Fusion）融合去重，编号命中直接置顶。
 * 任一路失败降级继续（检索永不阻断问答）。
 */
@Service
public class LawSearchService {

    private static final Logger log = LoggerFactory.getLogger(LawSearchService.class);

    /** 单路召回条数（RRF 融合前） */
    private static final int RECALL_K = 20;
    /** 融合后最终返回条数 */
    private static final int TOP_N = 15;
    /** RRF 平滑常数（标准值 60） */
    private static final int RRF_K = 60;

    /** 条号：阿拉伯「第311条」或中文「第三百一十一条」（含空格容错） */
    private static final Pattern ARTICLE_NO = Pattern.compile(
            "第\\s*([0-9]{1,4}|[一二三四五六七八九十百千零〇两]{1,12})\\s*条");

    /** 年份点名：「2024年真题」「看2023年的题」（真题检索的元查询信号） */
    private static final Pattern YEAR_MENTION = Pattern.compile("(19[0-9]{2}|20[0-9]{2})\\s*年");

    /** 俗称别名 → law_code */
    private static final Map<String, String> LAW_ALIASES = Map.of(
            "民诉法", "民事诉讼法",
            "刑诉法", "刑事诉讼法",
            "行诉法", "行政诉讼法",
            "破产法", "企业破产法",
            "担保制度解释", "担保制度");

    private final LawArticleRepository articleRepository;
    private final LawQuestionRepository questionRepository;
    private final LawEmbeddingClient embeddingClient;
    private final ObjectMapper objectMapper;
    /** RAG 检索耗时指标（/actuator/prometheus：rag_search_latency_*） */
    private final Timer searchLatency;

    public LawSearchService(LawArticleRepository articleRepository, LawQuestionRepository questionRepository,
            LawEmbeddingClient embeddingClient, ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.articleRepository = articleRepository;
        this.questionRepository = questionRepository;
        this.embeddingClient = embeddingClient;
        this.objectMapper = objectMapper;
        this.searchLatency = Timer.builder("rag.search.latency")
                .description("法学三路融合检索耗时").register(meterRegistry);
    }

    /** 前端引用气泡 */
    public record Citation(
            String lawName, String articleNo, Integer articleNoInt,
            String book, String chapter, String snippet) {

        static Citation of(LawArticleEntity e) {
            String content = e.getContent() == null ? "" : e.getContent();
            return new Citation(e.getLawName(), e.getArticleNo(), e.getArticleNoInt(),
                    e.getBook(), e.getChapter(),
                    content.length() > 80 ? content.substring(0, 80) + "…" : content);
        }
    }

    /** 检索结果（含融合分数与来源，source: exact / vector / trgm 及其组合） */
    public record SearchHit(LawArticleEntity article, double score, String source) {}

    /**
     * 融合检索主入口：query 为本轮用户消息文本。
     *
     * @param lawCodeHint 法名提示（编号路指代解析）：消息只写条号没写法名（"那第20条呢"）时，
     *                    用最近对话中出现过的法名兜底；消息自带法名以消息为准
     */
    public List<SearchHit> search(String query, String lawCodeHint) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        long start = System.currentTimeMillis();
        Map<Long, SearchHit> merged = new LinkedHashMap<>();

        // ① 编号精确（最高优先）
        long t0 = System.currentTimeMillis();
        SearchHit exact = exactHit(query, lawCodeHint);
        if (exact != null) {
            merged.put(exact.article().getId(), exact);
        }
        log.info("检索·编号路：{}（{}ms）",
                exact == null ? "未命中" : "命中 " + exact.article().getLawCode() + exact.article().getArticleNo(),
                System.currentTimeMillis() - t0);

        // ② 向量语义（失败降级）
        long t1 = System.currentTimeMillis();
        try {
            float[] qv = embeddingClient.embedOne(query);
            long embedCost = System.currentTimeMillis() - t1;
            List<LawArticleEntity> nearest = articleRepository.findNearest(toVectorLiteral(qv), RECALL_K);
            mergeRrf(merged, nearest, "vector");
            log.info("检索·向量路：召回 {} 条（embedding {}ms + SQL {}ms）",
                    nearest.size(), embedCost, System.currentTimeMillis() - t1 - embedCost);
        } catch (Exception e) {
            log.warn("向量检索降级（不影响问答）：{}", e.getMessage());
        }

        // ③ trgm 词汇（失败降级）
        long t2 = System.currentTimeMillis();
        try {
            List<LawArticleEntity> similar = articleRepository.findSimilarTrgm(query, RECALL_K);
            mergeRrf(merged, similar, "trgm");
            log.info("检索·trgm路：召回 {} 条（{}ms）", similar.size(), System.currentTimeMillis() - t2);
        } catch (Exception e) {
            log.warn("trgm 检索降级（不影响问答）：{}", e.getMessage());
        }

        List<SearchHit> top = merged.values().stream()
                .sorted(Comparator.comparingDouble(SearchHit::score).reversed())
                .limit(TOP_N)
                .toList();
        log.info("检索·融合收口：{} 条命中，总耗时 {}ms（{}）", top.size(), System.currentTimeMillis() - start,
                top.stream().map(h -> h.article().getLawCode() + h.article().getArticleNo() + "[" + h.source() + "]")
                        .collect(Collectors.joining("、")));
        searchLatency.record(Duration.ofMillis(System.currentTimeMillis() - start));
        return top;
    }

    /** 编号路：文本中的法名（含俗称）+ 条号 → (law_code, article_no_int) 精确命中；法名缺省时继承 hint */
    private SearchHit exactHit(String query, String lawCodeHint) {
        String code = matchLawName(query);
        if (code == null && lawCodeHint != null && !lawCodeHint.isBlank()) {
            code = lawCodeHint; // 「那第20条呢」：法名从上一轮对话继承
            log.info("编号路指代解析：法名继承 {}", lawCodeHint);
        }
        if (code == null) {
            return null;
        }
        Matcher m = ARTICLE_NO.matcher(query);
        if (!m.find()) {
            return null;
        }
        Integer no = ChineseNumeral.parse("第" + m.group(1) + "条");
        if (no == null) {
            return null;
        }
        return articleRepository.findByLawCodeAndArticleNoInt(code, no)
                .map(e -> new SearchHit(e, 1.0, "exact"))
                .orElse(null);
    }

    /** 探测文本中出现的法名（law_code）：供调用方从对话历史提取法名提示（编号路指代解析） */
    public String detectLawCode(String text) {
        return text == null || text.isBlank() ? null : matchLawName(text);
    }

    /** 法名匹配：查询文本命中的别名与 law_code，取更具体（更长）者 */
    private String matchLawName(String query) {
        String bestAlias = LAW_ALIASES.keySet().stream()
                .filter(query::contains)
                .max(Comparator.comparingInt(String::length))
                .orElse(null);
        String bestCode = articleRepository.findAllLawCodes().stream()
                .filter(query::contains)
                .max(Comparator.comparingInt(String::length))
                .orElse(null);
        if (bestAlias == null && bestCode == null) {
            return null;
        }
        if (bestCode == null || (bestAlias != null && bestAlias.length() > bestCode.length())) {
            return LAW_ALIASES.get(bestAlias);
        }
        return bestCode;
    }

    /** RRF 融合：rank 从 1 计，score += 1/(K+rank)；exact 条目不再累加（保持置顶） */
    private void mergeRrf(Map<Long, SearchHit> merged, List<LawArticleEntity> hits, String source) {
        for (int rank = 1; rank <= hits.size(); rank++) {
            LawArticleEntity e = hits.get(rank - 1);
            SearchHit existing = merged.get(e.getId());
            if (existing == null) {
                merged.put(e.getId(), new SearchHit(e, 1.0 / (RRF_K + rank), source));
            } else if (!"exact".equals(existing.source())) {
                merged.put(e.getId(), new SearchHit(existing.article(),
                        existing.score() + 1.0 / (RRF_K + rank),
                        existing.source() + "+" + source));
            }
        }
    }

    /** float[] → PG vector 字面量 '[v1,v2,…]' */
    private static String toVectorLiteral(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 10).append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }

    /** 检索结果转前端引用气泡 */
    public static List<Citation> toCitations(List<SearchHit> hits) {
        return hits.stream().map(h -> Citation.of(h.article())).collect(Collectors.toList());
    }

    /** 检索结果转 LLM 上下文（编号/书章定位 + 条文全文） */
    public static String toContext(List<SearchHit> hits) {
        if (hits.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("【法条库检索结果】（本轮问题相关，回答优先据此引用，标注《法名》第X条）\n");
        for (int i = 0; i < hits.size(); i++) {
            LawArticleEntity e = hits.get(i).article();
            sb.append(i + 1).append(".《").append(e.getLawName()).append("》").append(e.getArticleNo());
            if (e.getBook() != null && !e.getBook().isBlank()) {
                sb.append("〔").append(e.getBook());
                if (e.getChapter() != null && !e.getChapter().isBlank()) {
                    sb.append(" / ").append(e.getChapter());
                }
                sb.append("〕");
            }
            sb.append("：").append(e.getContent() == null ? "" : e.getContent().strip()).append('\n');
        }
        return sb.toString();
    }

    /** 真题检索：点名年份（「想看2024年真题」）走年份路直接查库；其余走向量语义召回（失败降级空列表，不阻断问答） */
    public List<LawQuestionEntity> searchExamQuestions(String query, int k) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        // 年份感知：元查询（点名看某年真题）与具体题干语义相似度极低，向量路无效，按年查库
        Matcher ym = YEAR_MENTION.matcher(query);
        if (ym.find()) {
            int year;
            try {
                year = Integer.parseInt(ym.group(1));
            } catch (NumberFormatException e) {
                year = -1;
            }
            if (year > 0) {
                List<LawQuestionEntity> byYear = questionRepository.findByYearOrigin(year, k);
                log.info("检索·真题路（年份 {}）：命中 {} 条", year, byYear.size());
                if (!byYear.isEmpty()) {
                    return byYear;
                }
                log.info("检索·真题路（年份 {}）：库中无该年真题，回退向量路", year);
            }
        }
        try {
            float[] qv = embeddingClient.embedOne(query);
            List<LawQuestionEntity> hits = questionRepository.findNearest(toVectorLiteral(qv), k);
            log.info("检索·真题路：召回 {} 条", hits.size());
            return hits;
        } catch (Exception e) {
            log.warn("真题检索降级（不影响问答）：{}", e.getMessage());
            return List.of();
        }
    }

    /** 真题 → 前端引用气泡（复用 Citation 结构，前端零改动） */
    public List<Citation> examCitations(List<LawQuestionEntity> questions) {
        List<Citation> result = new ArrayList<>(questions.size());
        for (LawQuestionEntity q : questions) {
            String stem = q.getStem() == null ? "" : q.getStem();
            String label = (q.getYear() == null ? "" : q.getYear() + "年") + "法考真题"
                    + (q.getQuestionType() == null ? "" : "·" + q.getQuestionType());
            result.add(new Citation(label, "", null, null, null,
                    stem.length() > 80 ? stem.substring(0, 80) + "…" : stem));
        }
        return result;
    }

    /** 真题 → LLM 上下文（题干 + 选项 + 答案 + 解析） */
    public String toExamContext(List<LawQuestionEntity> questions) {
        if (questions.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(
                "【历年真题参考】（与客人问题最相近的真题，回答可借鉴其考查角度与答案要点，讲解时自然带出，不必逐题复述）\n");
        for (int i = 0; i < questions.size(); i++) {
            LawQuestionEntity q = questions.get(i);
            sb.append(i + 1).append(".（").append(q.getYear() == null ? "" : q.getYear() + "年")
                    .append("·").append(q.getQuestionType()).append("）")
                    .append(q.getStem() == null ? "" : q.getStem()).append('\n');
            String opts = optionsLine(q.getOptions());
            if (!opts.isBlank()) {
                sb.append("  ").append(opts).append('\n');
            }
            if (q.getAnswer() != null && !q.getAnswer().isBlank()) {
                sb.append("  [答案] ").append(q.getAnswer());
                if (q.getAnalysis() != null && !q.getAnalysis().isBlank()) {
                    sb.append("  [解析] ").append(q.getAnalysis());
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /** optionsJson → 「A. xxx  B. xxx …」单行 */
    private String optionsLine(String optionsJson) {
        if (optionsJson == null || optionsJson.isBlank()) {
            return "";
        }
        try {
            List<?> list = objectMapper.readValue(optionsJson, List.class);
            StringBuilder sb = new StringBuilder();
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    sb.append(m.get("key")).append(". ").append(m.get("text")).append("  ");
                }
            }
            return sb.toString().strip();
        } catch (Exception e) {
            return optionsJson;
        }
    }
}
