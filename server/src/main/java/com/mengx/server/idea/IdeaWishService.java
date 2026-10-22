package com.mengx.server.idea;

import com.mengx.server.agent.AgentRunner;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 许愿池精灵（多 agent 协作 · 接力模式，区别于评审团的辩论与 PRD 车间的流水线）：
 * 提炼官（一句话愿望 → 四要素初稿）→ 追问官（初稿打磨成完整功能卡）。
 * 愿望炼成新芽卡的同时为用户自动开一场记录，回聊天页可继续打磨。
 * 同步执行约 20~60 秒，由前端给出等待反馈与超时。
 */
@Service
public class IdeaWishService {

    private static final Logger log = LoggerFactory.getLogger(IdeaWishService.class);

    /** 防重复比对的在册点子条数上限 */
    private static final int SHELF_MAX = 30;

    private static final String DISTILL_PROMPT = """
            你是许愿池的提炼官，负责把客人投进池子的一句话愿望，提炼成功能卡初稿。
            只依据愿望原文提炼：说得清的部分如实提炼，说不清的字段一律填「待聊」，绝不编造。
            严禁与"已收录的点子"重复——换一种说法、换个角度包装同一功能，都算重复。
            严格只输出 JSON（禁止 markdown 代码块、禁止解释文字）：
            {"title":"动宾短语标题","summary":"一句话功能说明","targetUser":"谁在用","triggerScene":"什么时机","reaction":"要什么反应","fallback":"边界情况"}

            已收录的点子（标题：说明）：
            {titles}

            客人的愿望：
            {wish}
            """;

    private static final String REFINE_PROMPT = """
            你是许愿池的追问官，负责把提炼官的功能卡初稿打磨成一张说得清楚的完整功能卡。
            初稿里填「待聊」的字段：能从愿望原文合理推断出客人想要什么的，补成自然的默认描述；
            实在无从推断的保留「待聊」。不得偏离愿望原意、不得扩大范围、不得与已有功能重复。
            creativity 是这张卡的新颖度评分，1~5 的整数。
            严格只输出 JSON（禁止 markdown 代码块、禁止解释文字）：
            {"title":"动宾短语标题","summary":"一句话功能说明","targetUser":"谁在用","triggerScene":"什么时机","reaction":"要什么反应","fallback":"边界情况","creativity":3}

            客人的愿望：
            {wish}

            提炼官的初稿：
            {draft}

            已收录的点子（标题：说明）：
            {titles}
            """;

    /** 提炼官产出的四要素初稿 */
    record WishDraft(String title, String summary, String targetUser, String triggerScene, String reaction, String fallback) {}

    /** 追问官产出的完整功能卡 */
    record WishCard(
            String title, String summary, String targetUser, String triggerScene, String reaction, String fallback, Integer creativity) {}

    private final AgentRunner agentRunner;
    private final IdeaCardRepository cardRepository;
    private final IdeaSessionRepository sessionRepository;
    private final IdeaMessageRepository messageRepository;

    public IdeaWishService(
            AgentRunner agentRunner,
            IdeaCardRepository cardRepository,
            IdeaSessionRepository sessionRepository,
            IdeaMessageRepository messageRepository) {
        this.agentRunner = agentRunner;
        this.cardRepository = cardRepository;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
    }

    /** 投一个愿望：两官接力炼成功能卡（新芽），失败或重复返回空 */
    public Optional<IdeaCardDto> cast(Long userId, String nickname, String wish) {
        List<IdeaCardEntity> existing = cardRepository
                .findAllByOrderByCreativityDescCreatedAtDesc()
                .stream().limit(SHELF_MAX).toList();

        // 第一棒 · 提炼官：愿望 → 初稿
        WishDraft draft = agentRunner
                .runForJson(DISTILL_PROMPT.replace("{titles}", shelfText(existing)), wish, WishDraft.class)
                .orElse(null);
        if (draft == null || draft.title() == null || draft.title().isBlank()) {
            log.warn("许愿池提炼官未产出初稿：{}", wish);
            return Optional.empty();
        }

        // 第二棒 · 追问官：初稿 → 完整功能卡（失败退回初稿，不算整体失败）
        WishCard refined = agentRunner
                .runForJson(
                        REFINE_PROMPT
                                .replace("{titles}", shelfText(existing))
                                .replace("{wish}", wish),
                        "提炼官的初稿 JSON：\n" + agentRunner.toJson(draft),
                        WishCard.class)
                .orElseGet(() -> new WishCard(
                        draft.title(), draft.summary(), draft.targetUser(),
                        draft.triggerScene(), draft.reaction(), draft.fallback(), 3));

        String title = truncate(or(refined.title(), draft.title()).trim(), 100);
        if (title.isBlank() || IdeaChatService.isDuplicateTitle(title, existing)) {
            log.info("拦截重复愿望：{}", title);
            return Optional.empty();
        }

        // 炼卡入库（新芽），并自动为这场愿望开一场记录，回聊天页可继续打磨
        IdeaSessionEntity session = sessionRepository.save(new IdeaSessionEntity(userId, nickname));
        IdeaCardEntity card = cardRepository.save(new IdeaCardEntity(
                session.getId(),
                nickname,
                title,
                orField(or(refined.summary(), draft.summary())),
                orField(or(refined.targetUser(), draft.targetUser())),
                orField(or(refined.triggerScene(), draft.triggerScene())),
                orField(or(refined.reaction(), draft.reaction())),
                orField(or(refined.fallback(), draft.fallback())),
                clamp(refined.creativity() == null ? 3 : refined.creativity())));
        messageRepository.saveAll(List.of(
                new IdeaMessageEntity(session.getId(), "user", wish),
                new IdeaMessageEntity(session.getId(), "assistant",
                        "许愿池的精灵把你的愿望炼成了功能卡「" + card.getTitle()
                                + "」，已收录。你可以继续说说哪里要改，或聊下一个灵感～"),
                new IdeaMessageEntity(session.getId(), "card", String.valueOf(card.getId()))));
        log.info("愿望炼卡成功：用户{} → 《{}》cardId={}", userId, card.getTitle(), card.getId());
        return Optional.of(IdeaCardDto.from(card));
    }

    private String shelfText(List<IdeaCardEntity> cards) {
        StringBuilder sb = new StringBuilder(cards.isEmpty() ? "（还没有）" : "");
        for (IdeaCardEntity card : cards) {
            sb.append("《").append(card.getTitle()).append("》：")
                    .append(card.getSummary() == null ? "" : card.getSummary()).append('\n');
        }
        return sb.toString().trim();
    }

    private static String or(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String orField(String value) {
        return value == null || value.isBlank() ? "待聊" : truncate(value.trim(), 200);
    }

    private static int clamp(int value) {
        return Math.max(1, Math.min(5, value));
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
