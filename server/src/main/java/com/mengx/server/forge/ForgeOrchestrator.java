package com.mengx.server.forge;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mengx.server.idea.IdeaCardEntity;
import com.mengx.server.idea.IdeaCardRepository;
import com.mengx.server.llm.LlmUsage;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 工部编排器（方案 §6.1/§7）：掌握流程的是代码不是 agent——单工单岗链调度、四态指令状态机、
 * 黑板落盘 + CAS 转移 + 卡片镜像、失败显式上抛。实现 ForgeStageRunner（Step 3 接口）即自动接通：
 * ① 开工提交（ForgeService）② resume（Step 5 造物线答案到达）③ 开机恢复钩子。
 *
 * 执行纪律（§7.5）：岗任务幂等（只认黑板输入重写本岗段）；先落盘后交棒（casStageResult 一次原子 UPDATE）；
 * 线程池里不存在任何等待；用量打标 forge.* 全链路显式 set、finally 必清。
 */
@Service
public class ForgeOrchestrator implements ForgeStageRunner {

    private static final Logger log = LoggerFactory.getLogger(ForgeOrchestrator.class);

    /** 岗链（§7.2 串行单岗链）：固定顺序，岗 bean 落位即自动接入（Step 4 仅 analyst，后续步骤零改编排） */
    private static final List<String> CHAIN = List.of("analyst", "advisor", "architect", "reviewer");

    /** 审校打回硬上限（§6.1），超限转 BLOCKED 上看板 */
    private static final int MAX_ROUNDS = 2;

    private static final String STAGE_ANALYST = "analyst";
    private static final String STAGE_REVIEWER = "reviewer";

    private final Map<String, ForgeAgent> agents;
    private final ForgeJobRepository jobRepository;
    private final IdeaCardRepository cardRepository;
    private final ForgeExecutor executor;
    private final ForgeHeraldService herald;
    private final AdvisorRegistry advisorRegistry;
    private final TransactionTemplate tx;

    /** 选型文本拼装用（advisor 槽位按道场类型选顾问，需把产品段序列化成文本匹配信号词） */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public ForgeOrchestrator(List<ForgeAgent> agents,
                             ForgeJobRepository jobRepository,
                             IdeaCardRepository cardRepository,
                             ForgeExecutor executor,
                             ForgeHeraldService herald,
                             AdvisorRegistry advisorRegistry,
                             PlatformTransactionManager transactionManager) {
        this.agents = agents.stream()
                .collect(Collectors.toUnmodifiableMap(ForgeAgent::name, a -> a));
        this.jobRepository = jobRepository;
        this.cardRepository = cardRepository;
        this.executor = executor;
        this.herald = herald;
        this.advisorRegistry = advisorRegistry;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Override
    public void runStage(ForgeJobEntity jobRef) {
        runStageInternal(jobRef, null, null);
    }

    /**
     * resume（方案 §7.4 触发源①）：客人答案到达，挂起岗带答案重跑（stage 不变，
     * pendingQuestions 已由 casResume 清空——问题与答案经此参数对传入，语义消化归抛出岗，§5.2）。
     * 异步提交工部线程池（方案 A：chat/stream 分身确认即关流，resume 后台跑不阻塞请求线程）。
     */
    public void resumeStage(ForgeJobEntity jobRef, PendingQuestion question, String answer) {
        executor.submitStage(jobRef.getId(), () -> runStageInternal(jobRef, question, answer));
    }

    private void runStageInternal(ForgeJobEntity jobRef, PendingQuestion question, String answer) {
        Long jobId = jobRef.getId();
        try {
            // 重读断点：三触发源传入的实体可能已过时（提交点不同），一律以库中最新行为准
            ForgeJobEntity job = jobRepository.findById(jobId).orElse(null);
            if (job == null) {
                log.warn("工单 {} 不存在，岗任务丢弃", jobId);
                return;
            }
            if (!ForgeJobEntity.STATUS_RUNNING.equals(job.getStatus())) {
                log.info("工单 {} 已被其他触发源推进到 {}，本次岗任务丢弃", jobId, job.getStatus());
                return;
            }
            String stage = stageOf(job);
            IdeaCardEntity card = cardRepository.findById(job.getCardId()).orElse(null);
            if (card == null) {
                fail(jobId, "来源功能卡已不存在（cardId=" + job.getCardId() + "）");
                return;
            }
            ForgeBlackboard board = ForgeBlackboard.load(job);
            // 岗位解析：advisor 为插拔槽位（每次执行按道场类型重新选型），其余按名直取
            ForgeAgent agent = resolveAgent(stage, card, board);
            if (agent == null) {
                // 岗位尚未上线（后续步骤落位）：黑板原样暂存，从断点可重试推进
                log.warn("工单 {} 当前岗 {} 未注册，暂存看板待后续版本", jobId, stage);
                settle(jobId, job.getCardId(), board, null,
                        ForgeJobEntity.STATUS_BLOCKED, stage, roundsOf(job),
                        "岗位「" + stage + "」尚未上线，工单暂存；后续版本接入后可从断点重试");
                return;
            }
            long startAt = System.currentTimeMillis();
            AgentResult result;
            LlmUsage.set("forge." + agent.name()); // 用量打标：具体到 advisor.legal/default 岗名分账（§6.2）
            try {
                result = agent.run(new ForgeContext(job, board, card, question, answer));
            } finally {
                LlmUsage.clear();
            }
            log.info("工单 {} 岗 {} 完成（耗时 {}ms，指令 {}）", jobId, agent.name(),
                    System.currentTimeMillis() - startAt, result.instruction());
            applyResult(job, board, result);
        } catch (Throwable e) {
            log.warn("工单 {} 岗执行异常（标 FAILED，断点保留）：{}", jobId, e.toString());
            fail(jobId, "岗执行异常：" + e);
        }
    }

    /** 四态指令 → 状态机（§7.2）：CONTINUE 推下一岗 / WAIT 挂起等人 / REWRITE 定向重跑（≤2 轮）/ BLOCK 上看板 */
    private void applyResult(ForgeJobEntity job, ForgeBlackboard board, AgentResult result) {
        Long jobId = job.getId();
        Long cardId = job.getCardId();
        String stage = stageOf(job);
        int rounds = roundsOf(job);
        String instruction = instructionOf(result);
        if ("WAIT".equals(instruction) && result.question() == null) {
            instruction = "CONTINUE"; // 没带问题的 WAIT 会挂死工单，按完成推进
        }
        switch (instruction) {
            case "WAIT" -> {
                // 挂起落定后由传话工序把问题转述成分身消息落库（前端轮询可见，§9 消息下发模型）；
                // settle=false 说明已被并发触发源推进，不重复转述
                if (settle(jobId, cardId, board, result.question(),
                        ForgeJobEntity.STATUS_WAITING_USER, stage, rounds, null)) {
                    herald.postQuestion(jobId, result.question());
                }
            }
            case "BLOCK" -> settle(jobId, cardId, board, null,
                    ForgeJobEntity.STATUS_BLOCKED, stage, rounds,
                    isBlank(result.note()) ? "岗判定无法推进" : result.note());
            case "REWRITE" -> {
                int next = rounds + 1;
                if (next > MAX_ROUNDS) {
                    settle(jobId, cardId, board, null, ForgeJobEntity.STATUS_BLOCKED, stage, next,
                            "打回超过 " + MAX_ROUNDS + " 轮上限：" + or(result.rewriteReason(), result.note()));
                } else {
                    String target = isBlank(result.rewriteTarget()) ? stage : result.rewriteTarget().trim();
                    if (!isRegistered(target)) {
                        target = stage; // 打回目标未上线/写错岗名：留在当前岗本轮
                    }
                    if (settle(jobId, cardId, board, null,
                            ForgeJobEntity.STATUS_RUNNING, target, next, result.rewriteReason())) {
                        executor.submitStage(jobId, () -> runStage(job));
                    }
                }
            }
            default -> {
                // CONTINUE：审校岗通过即交主理人终审（审校意见摘要落工单备注，终审参考）；
                // 其余按岗链推下一注册岗
                if (STAGE_REVIEWER.equals(stage)) {
                    settle(jobId, cardId, board, null,
                            ForgeJobEntity.STATUS_REVIEW, stage, rounds, result.note());
                    return;
                }
                String next = nextRegisteredStage(stage);
                if (next == null) {
                    String missing = missingStages(stage).stream().collect(Collectors.joining("/"));
                    settle(jobId, cardId, board, null, ForgeJobEntity.STATUS_BLOCKED, stage, rounds,
                            "流水线后续岗位（" + missing + "）尚未上线，工单暂存；后续版本接入后可从断点重试");
                } else if (settle(jobId, cardId, board, null,
                        ForgeJobEntity.STATUS_RUNNING, next, rounds, null)) {
                    executor.submitStage(jobId, () -> runStage(job));
                }
            }
        }
    }

    /**
     * 落盘黑板 + CAS 状态转移 + 卡片镜像，单事务原子完成；返回 false = 已被其他触发源推进（本次丢弃）。
     * 挂起问题整体替换：WAIT 写入新问题，其余转移清空（清空即「已答」幂等闸的落库形态）。
     */
    private boolean settle(Long jobId, Long cardId, ForgeBlackboard board, PendingQuestion question,
                           String to, String stage, int rounds, String note) {
        Boolean ok = tx.execute(status -> {
            int updated = jobRepository.casStageResult(
                    jobId, board.toJson(),
                    question == null ? null : PendingQuestion.envelope(question),
                    to, stage, rounds, abbreviate(note), Instant.now());
            if (updated == 0) {
                log.info("工单 {} 已被其他触发源推进，本次转移丢弃（→ {}）", jobId, to);
                return false;
            }
            // 卡片镜像：buildStatus 随工单状态流转（DONE 出稿由排版官带产物落 buildJson）
            cardRepository.findById(cardId).ifPresent(c -> {
                c.markBuild(to, null);
                cardRepository.save(c);
            });
            return true;
        });
        return Boolean.TRUE.equals(ok);
    }

    /** 岗执行异常兜底：FAILED 带说明 + 卡片镜像；CAS 0 行说明已被自行转移，跳过 */
    private void fail(Long jobId, String note) {
        try {
            tx.execute(status -> {
                ForgeJobEntity job = jobRepository.findById(jobId).orElse(null);
                if (job == null) {
                    return null;
                }
                if (jobRepository.casFail(jobId, abbreviate(note), Instant.now()) > 0) {
                    cardRepository.findById(job.getCardId()).ifPresent(c -> {
                        c.markBuild(ForgeJobEntity.STATUS_FAILED, null);
                        cardRepository.save(c);
                    });
                }
                return null;
            });
        } catch (Exception e) {
            log.error("工单 {} FAILED 落库失败：{}", jobId, e.getMessage());
        }
    }

    /** 岗位注册判定：advisor 为插拔槽位（advisor.* 任一注册即视为上线），其余岗按 bean 名精确匹配 */
    private boolean isRegistered(String stage) {
        if (AdvisorRegistry.SLOT.equals(stage)) {
            return advisorRegistry.anyRegistered();
        }
        return agents.containsKey(stage);
    }

    /**
     * 岗位解析：advisor 槽位每次执行经 AdvisorRegistry 按道场类型重新选型——幂等岗任务语义下安全
     * （顾问只认产品段输入重写领域段，产品段被答案修订后类型判定应重新发生）；其余岗按名直取。
     * 选型文本 = 卡标题 + 一句话 + 产品段 JSON，信号词只认定位级表述（§1.2 酸儒教训）。
     */
    private ForgeAgent resolveAgent(String stage, IdeaCardEntity card, ForgeBlackboard board) {
        if (AdvisorRegistry.SLOT.equals(stage)) {
            String text = card.getTitle() + " " + or(card.getSummary(), "")
                    + " " + jsonOf(board.getProduct());
            return advisorRegistry.choose(text);
        }
        return agents.get(stage);
    }

    private static String jsonOf(Object value) {
        if (value == null) {
            return "";
        }
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    /** 岗链中当前岗之后第一个已注册岗；无（后续岗均未上线/链走完）返回 null */
    private String nextRegisteredStage(String stage) {
        int idx = CHAIN.indexOf(stage);
        for (int i = idx + 1; i < CHAIN.size(); i++) {
            if (isRegistered(CHAIN.get(i))) {
                return CHAIN.get(i);
            }
        }
        return null;
    }

    /** 当前岗之后尚未上线的岗名（暂存说明用） */
    private List<String> missingStages(String stage) {
        int idx = CHAIN.indexOf(stage);
        return CHAIN.subList(idx + 1, CHAIN.size()).stream()
                .filter(s -> !isRegistered(s))
                .toList();
    }

    /** 指令归一：未知指令按有无挂起问题推断，WAIT 没问题由调用方改 CONTINUE */
    private static String instructionOf(AgentResult result) {
        String i = result.instruction() == null ? "" : result.instruction().trim().toUpperCase();
        return switch (i) {
            case "WAIT", "BLOCK", "REWRITE", "CONTINUE" -> i;
            default -> result.question() != null ? "WAIT" : "CONTINUE";
        };
    }

    private static String stageOf(ForgeJobEntity job) {
        return isBlank(job.getStage()) ? STAGE_ANALYST : job.getStage();
    }

    private static int roundsOf(ForgeJobEntity job) {
        return job.getRounds() == null ? 0 : job.getRounds();
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 490 ? s.substring(0, 490) : s;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String or(String s, String fallback) {
        return isBlank(s) ? fallback : s;
    }
}
