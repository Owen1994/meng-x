package com.mengx.server.idea;

import com.mengx.server.agent.AgentRunner;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * PRD 格式化官（车间瘦身后只剩一道工序）：评审与设计已前置到仙官与仙班的共创对话里，
 * 这里只把功能卡与方案卷轴格式化成施工文档——单次调用，不新增设计、不遗漏验收标准。
 * 按卡片体量分流：功能卡出施工 PRD，应用卡出「应用蓝图文档」（模块/数据/集成/分期）。
 * 产出为 Markdown 文本直接存 prdJson，可直接投喂 AI 编程工具。
 */
@Service
public class IdeaPrdService {

    private static final Logger log = LoggerFactory.getLogger(IdeaPrdService.class);

    /** 现有技术栈（格式化出的 PRD 必须贴合，不得另起炉灶） */
    private static final String STACK = """
            现有技术栈（PRD 必须贴合，不得另起炉灶）：
            - 前端：React 18 + Vite 6 单页应用，无路由库，手机端微信打开为主场景（视口自适应）
            - 后端：Java 17 + Spring Boot 3.4，Spring Data JPA（ddl-auto=update），REST 接口 JWT 鉴权
            - 数据库：PostgreSQL
            - 已有体系：手机号登录即注册、聊天流式输出（SSE）、功能卡（四要素）、看板、每日一签、修为墙""";

    private static final String FORMAT_PROMPT = """
            你是 PRD 车间的格式化官。这张功能卡是一个单一功能的小点子（体量=功能），它的评审与设计已经在仙官和客人的对话中完成，
            你只负责格式化成稿：把功能卡四要素与方案卷轴整理成一份完整 PRD（Markdown 格式），
            结构必须包含以下章节：
            # 标题（功能名）
            ## 一句话说明
            ## 背景与目标
            ## 用户场景
            ## 功能需求
            ## 页面与交互
            ## 数据与接口
            ## 边界与异常
            ## 验收标准（可逐条核对的清单）

            铁律：
            - 不新增设计：功能细节以功能卡四要素与方案卷轴为准，卷轴写到的必须收录
            - 不遗漏：方案卷轴的验收标准必须完整进入「验收标准」章节，逐条可核对
            - 卷轴某处为"待聊"或缺失时，按四要素补一段最小可行方案，不擅自扩大范围
            - 这份 PRD 将直接投喂给 AI 编程工具在现有技术栈上实现，必须自包含、无歧义、无占位符
            只输出 PRD 正文本身，不要任何前言或解释。""";

    /** 应用卡的格式化模板：出「应用蓝图文档」（模块/数据/集成/分期） */
    private static final String BLUEPRINT_PROMPT = """
            你是 PRD 车间的格式化官。这张应用卡是一个复合大点子（体量=应用，要拆多个模块），
            它的评审与设计已经在仙官和客人的对话中完成，你只负责格式化成稿：
            把应用卡四要素与方案卷轴（含应用蓝图）整理成一份「应用蓝图文档」（Markdown 格式），
            结构必须包含以下章节：
            # 标题（应用名）
            ## 一句话说明
            ## 背景与目标
            ## 应用架构（模块划分：每个模块干什么、模块之间怎么配合）
            ## 数据与信息来源
            ## 与现有应用的集成方案（客人从哪里进入、复用现有应用的什么）
            ## 分期实现路径（第一期做什么、之后做什么）
            ## 第一期验收标准（可逐条核对的清单）

            铁律：
            - 不新增设计：以应用卡四要素与方案卷轴（含蓝图）为准，蓝图写到的必须收录
            - 不遗漏：卷轴的验收标准与蓝图分期必须完整进入对应章节，逐条可核对
            - 这份蓝图文档将直接投喂给 AI 编程工具在现有技术栈上搭建骨架，必须自包含、无歧义、无占位符
            只输出正文本身，不要任何前言或解释。""";

    private final AgentRunner agentRunner;
    private final IdeaCardRepository cardRepository;

    public IdeaPrdService(AgentRunner agentRunner, IdeaCardRepository cardRepository) {
        this.agentRunner = agentRunner;
        this.cardRepository = cardRepository;
    }

    /** 异步开进车间（看板手动触发），不阻塞调用方 */
    public void generateAsync(Long cardId) {
        CompletableFuture.runAsync(() -> {
            try {
                generateNow(cardId);
            } catch (Exception e) {
                log.warn("PRD 车间异步执行异常 cardId={}: {}", cardId, e.getMessage());
            }
        });
    }

    /** 同步格式化一次：功能卡 + 方案卷轴 → 施工 PRD，markdown 直存 prdJson */
    public void generateNow(Long cardId) {
        IdeaCardEntity card = cardRepository.findById(cardId).orElse(null);
        if (card == null) {
            return;
        }
        card.markPrd(IdeaCardEntity.PRD_RUNNING, null);
        cardRepository.saveAndFlush(card);
        String prdStatus;
        String prdJson = null;
        try {
            // 体量分流：功能卡出施工 PRD，应用卡出应用蓝图文档
            String prompt = IdeaCardEntity.SCALE_APP.equals(card.getScale()) ? BLUEPRINT_PROMPT : FORMAT_PROMPT;
            String markdown = agentRunner.run(prompt, buildContext(card) + "\n\n" + STACK);
            prdJson = truncate(markdown, 12000);
            prdStatus = IdeaCardEntity.PRD_DONE;
        } catch (Exception e) {
            log.warn("PRD 格式化失败 cardId={}: {}", cardId, e.getMessage());
            prdStatus = IdeaCardEntity.PRD_FAILED;
        }
        // agent 调用期间卡片可能已被流转或删除：重读最新行落 PRD 结果，避免旧快照覆盖状态机
        final String status = prdStatus;
        final String json = prdJson;
        cardRepository.findById(cardId).ifPresent(fresh -> {
            fresh.markPrd(status, json);
            cardRepository.save(fresh);
        });
    }

    /** 取一张卡的 PRD（markdown 文本，下载/复制用），未出稿返回空 */
    public Optional<String> getMarkdown(Long cardId) {
        return cardRepository
                .findById(cardId)
                .map(IdeaCardEntity::getPrdJson)
                .filter(Objects::nonNull)
                .filter(md -> !md.isBlank());
    }

    /** 组装格式化输入：功能卡四要素 + 优先级 + 方案卷轴（设计沉淀以此为准） */
    private String buildContext(IdeaCardEntity card) {
        return """
                【功能卡】
                标题：%s
                一句话：%s
                谁在用：%s
                什么时机用：%s
                要什么反应：%s
                没有它时怎么办：%s
                创意值：%d 星
                体量：%s
                优先级：%s
                提出人：%s

                【方案卷轴】（仙官与客人共创的设计沉淀，格式化以此为准）
                %s
                """.formatted(
                card.getTitle(),
                card.getSummary(),
                or(card.getTargetUser()),
                or(card.getTriggerScene()),
                or(card.getReaction()),
                or(card.getFallback()),
                card.getCreativity(),
                card.getScale() == null ? IdeaCardEntity.SCALE_FUNCTION : card.getScale(),
                card.getPriority() == null ? "中" : card.getPriority(),
                card.getNickname(),
                card.getDesignJson() == null || card.getDesignJson().isBlank()
                        ? "（无卷轴：按四要素补最小可行方案，不擅自扩大范围）"
                        : card.getDesignJson());
    }

    private static String or(String s) {
        return s == null || s.isBlank() ? "待聊" : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) : s;
    }
}
