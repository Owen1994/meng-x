package com.mengx.server.law;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * RAG 数据手动导入：类型区分（statute 法条 / interpretation 司法解释 / question 真题 / note 教材），
 * 支持批量清单（laws）依次导入；异步执行 + 内存进度查询；幂等锚点 content_hash，重复触发安全。
 * 同时只允许一个导入任务在跑（数据源量级小、embedding 有速率限制，无需并发）。
 */
@Service
public class LawImportService {

    private static final Logger log = LoggerFactory.getLogger(LawImportService.class);

    /** 导入进度快照（status: RUNNING / DONE / FAILED）；lawIndex/laws 支撑批量进度展示 */
    public record Progress(
            String type, String law, String status,
            int total, int imported, int skipped, int failed,
            String startedAt, String finishedAt, String error,
            int lawIndex, List<String> laws) {
        static Progress idle() {
            return new Progress(null, null, "IDLE", 0, 0, 0, 0, null, null, null, 0, List.of());
        }
    }

    private final LawArticleRepository articleRepository;
    private final LawArticleParser articleParser;
    private final LawQuestionRepository questionRepository;
    private final LawQuestionParser questionParser;
    private final LawEmbeddingClient embeddingClient;

    @Value("${mengx.law.data-dir:./data/law}")
    private String dataDir;

    @Value("${mengx.law.question-dir:}")
    private String questionDir;

    /** 单任务互斥 + 进度快照 */
    private final Object lock = new Object();
    private volatile Progress progress = Progress.idle();

    public LawImportService(
            LawArticleRepository articleRepository,
            LawArticleParser articleParser,
            LawQuestionRepository questionRepository,
            LawQuestionParser questionParser,
            LawEmbeddingClient embeddingClient) {
        this.articleRepository = articleRepository;
        this.articleParser = articleParser;
        this.questionRepository = questionRepository;
        this.questionParser = questionParser;
        this.embeddingClient = embeddingClient;
    }

    public Progress progress() {
        return progress;
    }

    /** 手动触发导入（单部或批量清单）：立即返回，任务在后台线程依次执行。
     *  question 类型 laws 为年份（"2025"），支持 "all" 展开为真题库全部年份 */
    public Progress trigger(String type, List<String> laws) {
        List<String> queue = laws.stream().filter(l -> l != null && !l.isBlank()).map(String::trim).distinct().toList();
        if (queue.isEmpty()) {
            throw new IllegalArgumentException("导入清单为空");
        }
        if ("question".equals(type) && queue.contains("all")) {
            List<String> expanded = new ArrayList<>();
            for (String l : queue) {
                if ("all".equalsIgnoreCase(l)) {
                    expanded.addAll(questionParser.availableYears(questionDir));
                } else {
                    expanded.add(l);
                }
            }
            queue = expanded.stream().distinct().sorted().toList();
            if (queue.isEmpty()) {
                throw new IllegalStateException("真题库无可用年份（目录：" + questionDir + "）");
            }
        }
        List<String> taskQueue = queue; // lambda 引用需 effectively final
        synchronized (lock) {
            if ("RUNNING".equals(progress.status())) {
                throw new IllegalStateException("已有导入任务在跑（" + progress.lawIndex() + "/" + progress.laws().size() + " " + progress.law() + "），等它结束再触发");
            }
            progress = new Progress(type, taskQueue.get(0), "RUNNING", 0, 0, 0, 0,
                    java.time.Instant.now().toString(), null, null, 0, taskQueue);
        }
        Thread worker = new Thread(() -> run(type, taskQueue), "law-import");
        worker.setDaemon(true);
        worker.start();
        return progress;
    }

    private void run(String type, List<String> laws) {
        List<String> failures = new ArrayList<>();
        for (int i = 0; i < laws.size(); i++) {
            String law = laws.get(i);
            try {
                switch (type) {
                    case "statute", "interpretation" -> importArticles(type, law, i + 1);
                    case "question" -> importQuestions(law, i + 1);
                    case "note" -> throw new IllegalArgumentException("教材导入未实现（W2 交付）");
                    default -> throw new IllegalArgumentException("未知导入类型：" + type);
                }
            } catch (Exception e) {
                log.error("导入失败 type={} law={}", type, law, e);
                failures.add(law + "：" + e.getMessage());
            }
        }
        String summary = failures.isEmpty() ? null
                : failures.size() + " 部失败 → " + String.join("；", failures);
        progress = new Progress(type, laws.get(laws.size() - 1), "DONE",
                progress.total(), progress.imported(), progress.skipped(), progress.failed(),
                progress.startedAt(), java.time.Instant.now().toString(), summary,
                laws.size(), progress.laws());
        log.info("批量导入完成 type={}：{} 部（{}）total={} imported={} skipped={} failed={} {}", type, laws.size(),
                String.join("、", laws), progress.total(), progress.imported(), progress.skipped(), progress.failed(),
                summary == null ? "" : summary);
    }

    /** 法条/司法解释导入：解析 → hash 幂等比对 → 批量 embedding → upsert（total 跨法律累计） */
    private void importArticles(String lawType, String law, int lawIndex) {
        progress = new Progress(lawType, law, "RUNNING", progress.total(), progress.imported(),
                progress.skipped(), progress.failed(), progress.startedAt(), null, null, lawIndex, progress.laws());
        List<LawArticleEntity> fresh = articleParser.parse(dataDir, lawType, law);
        if (fresh.isEmpty()) {
            throw new IllegalStateException("未解析到任何条目：type=" + lawType + " law=" + law);
        }
        progress = new Progress(lawType, law, "RUNNING", progress.total() + fresh.size(), progress.imported(),
                progress.skipped(), progress.failed(), progress.startedAt(), null, null, lawIndex, progress.laws());
        log.info("[{}/{}] {} 解析 {} 条，开始幂等比对与向量化", lawIndex, progress.laws().size(), law, fresh.size());

        // 幂等比对：库内已有相同 hash 的条目跳过（内容未变化）
        List<String> hashes = fresh.stream().map(LawArticleEntity::getContentHash).toList();
        var existing = articleRepository.findByContentHashIn(hashes);
        java.util.Set<String> known = new java.util.HashSet<>();
        for (var e : existing) {
            known.add(e.getContentHash());
        }

        List<LawArticleEntity> pending = new ArrayList<>();
        for (LawArticleEntity e : fresh) {
            if (known.contains(e.getContentHash())) {
                bump(0, 1, 0);
            } else {
                pending.add(e);
            }
        }

        if (pending.isEmpty()) {
            log.info("{} 全部条目已存在（hash 幂等跳过）", law);
            return;
        }

        // 批量向量化（客户端内部已做 16/批 + 限速 + 退避）
        List<float[]> vectors = embeddingClient.embed(
                pending.stream().map(e -> e.getLawName() + " " + e.getArticleNo() + " " + e.getContent()).toList());
        for (int i = 0; i < pending.size(); i++) {
            pending.get(i).setEmbedding(vectors.get(i));
        }

        // upsert：(law_code, article_no) 锚点——新条插入，修订条整体刷新
        for (LawArticleEntity e : pending) {
            try {
                articleRepository.findByLawCodeAndArticleNo(e.getLawCode(), e.getArticleNo())
                        .ifPresentOrElse(
                                old -> {
                                    old.revise(e);
                                    articleRepository.save(old);
                                },
                                () -> articleRepository.save(e));
                bump(1, 0, 0);
            } catch (Exception ex) {
                log.warn("法条入库失败 {} {}: {}", e.getLawCode(), e.getArticleNo(), ex.getMessage());
                bump(0, 0, 1);
            }
        }
    }

    /** 真题导入：law 为年份。解析 → hash 幂等比对 → 批量 embedding → 落库（total 跨年份累计） */
    private void importQuestions(String year, int lawIndex) {
        if (questionDir == null || questionDir.isBlank()) {
            throw new IllegalStateException("未配置真题库目录 mengx.law.question-dir");
        }
        progress = new Progress("question", year, "RUNNING", progress.total(), progress.imported(),
                progress.skipped(), progress.failed(), progress.startedAt(), null, null, lawIndex, progress.laws());
        List<LawQuestionEntity> fresh = questionParser.parse(questionDir, year);
        if (fresh.isEmpty()) {
            log.warn("{} 年真题解析结果为空（源文件乱序损坏），该年份按 0 题跳过", year);
            return;
        }
        progress = new Progress("question", year, "RUNNING", progress.total() + fresh.size(), progress.imported(),
                progress.skipped(), progress.failed(), progress.startedAt(), null, null, lawIndex, progress.laws());
        log.info("[{}/{}] {} 年真题解析 {} 题，开始幂等比对与向量化", lawIndex, progress.laws().size(), year, fresh.size());

        // 幂等比对：库内已有相同 hash 的题目跳过
        List<String> hashes = fresh.stream().map(LawQuestionEntity::getContentHash).toList();
        java.util.Set<String> known = new java.util.HashSet<>();
        for (LawQuestionEntity e : questionRepository.findByContentHashIn(hashes)) {
            known.add(e.getContentHash());
        }

        List<LawQuestionEntity> pending = new ArrayList<>();
        for (LawQuestionEntity e : fresh) {
            if (known.contains(e.getContentHash())) {
                bump(0, 1, 0);
            } else {
                pending.add(e);
            }
        }
        if (pending.isEmpty()) {
            log.info("{} 年真题全部题目已存在（hash 幂等跳过）", year);
            return;
        }

        // 批量向量化（客户端内部已做 16/批 + 限速 + 退避）；题干 + 选项 json 拼接为语义块
        List<float[]> vectors = embeddingClient.embed(
                pending.stream().map(e -> e.getStem() + " " + e.getOptions()).toList());
        for (int i = 0; i < pending.size(); i++) {
            pending.get(i).setEmbedding(vectors.get(i));
        }

        for (LawQuestionEntity e : pending) {
            try {
                questionRepository.save(e);
                bump(1, 0, 0);
            } catch (Exception ex) {
                log.warn("真题入库失败 {} 年：{}", year, ex.getMessage());
                bump(0, 0, 1);
            }
        }
    }

    /** 进度计数递增（其余字段保持当前值） */
    private Progress bump(int imported, int skipped, int failed) {
        Progress p = progress;
        return progress = new Progress(p.type(), p.law(), p.status(),
                p.total(), p.imported() + imported, p.skipped() + skipped, p.failed() + failed,
                p.startedAt(), p.finishedAt(), p.error(), p.lawIndex(), p.laws());
    }
}
