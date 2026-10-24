package com.mengx.server.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mengx.server.llm.LlmUsage;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 分层路由器：每条用户消息经三层裁决进入通道——
 * L1 确定性信号层（注册表信号词，零成本零误差，消化大多数消息）；
 * L2 上下文细判（消息 + 最近几轮对话背景 + 注册表名册 → glm-4-flash，仅灰区消息走到这）；
 * L3 兜底（解析失败/输出未知通道 → 粘住当前通道，宁可不切，不可切错）。
 * L0 显式直达（入口点击、通道指令）在调用方完成，不进本类。
 */
@Service
public class RoutingService {

    private static final Logger log = LoggerFactory.getLogger(RoutingService.class);

    private final ObjectProvider<ChatModel> routeModelProvider;
    private final ChannelRegistry registry;
    private final ObjectMapper objectMapper;

    public RoutingService(
            @Qualifier("routeChatModel") ObjectProvider<ChatModel> routeModelProvider,
            ChannelRegistry registry,
            ObjectMapper objectMapper) {
        this.routeModelProvider = routeModelProvider;
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    /**
     * 裁决本轮消息进入哪个通道。
     *
     * @param text          本轮用户消息
     * @param lastChannelId 上一轮回答所在通道（粘性基准 + L3 兜底去向），null 视为默认通道
     * @param recentContext 最近几轮对话背景（客人/回答 逐行，由调用方从会话历史裁剪，不含本轮消息）
     */
    public String route(String text, String lastChannelId, String recentContext) {
        final String last =
                lastChannelId == null ? ChannelRegistry.DEFAULT_CHANNEL : lastChannelId;
        if (text == null || text.isBlank()) {
            return last;
        }
        // ---- L0.5 强意图直达：明确的能力请求（出题/刷题/考考我…）无视通道粘性直达对应通道 ----
        // 这类信号是无歧义的「用某能力」请求，交给 L2 模型细判反而可能误判（glm-4-flash 遵循度有限）
        for (ChannelSpec spec : registry.all()) {
            if (spec.id().equals(ChannelRegistry.DEFAULT_CHANNEL) || spec.strongSignals().isEmpty()) {
                continue;
            }
            for (String s : spec.strongSignals()) {
                if (text.contains(s)) {
                    log.info("路由强意图直达（{} 命中「{}」）：{}", spec.name(), s, compact(text));
                    return spec.id();
                }
            }
        }
        // ---- L1 确定性信号层 ----
        java.util.List<ChannelSpec> hits = registry.signalMatches(text);
        boolean inDefault = ChannelRegistry.DEFAULT_CHANNEL.equals(last);
        // 默认通道 + 无任何专项信号 → 直接留在默认通道（泛话永远不可能突然切专项）
        if (inDefault && hits.isEmpty()) {
            log.info("路由 L1 短路（默认通道且无专项信号 → host 主对话代理）：{}", compact(text));
            return ChannelRegistry.DEFAULT_CHANNEL;
        }
        // 专项通道 + 命中本通道信号 → 续通道（法学问答中的"那诈骗罪呢"无需再分类）
        if (!inDefault && hits.size() == 1 && hits.get(0).id().equals(last)) {
            log.info("路由 L1 续通道（命中 {} 信号）：{}", last, compact(text));
            return last;
        }
        // ---- L2 上下文细判（灰区才走到这）----
        ChatModel model = routeModelProvider.getIfAvailable();
        if (model == null) {
            return last;
        }
        String prompt = """
                你是消息路由器。判断本轮用户消息该交给哪个通道作答。

                【通道名册】
                %s

                【最近对话背景】（当前通道：%s）
                %s

                【判断规则】
                1. 若当前在专项通道，只有本轮消息是该专项话题的延续才留在原通道；客人开启新话题（点子、闲聊）就归默认通道（host）
                2. 分析、总结、继续、案例这类泛用词本身不代表专项问题，必须消息整体在问该领域才切专项通道
                3. 拿不准时选默认通道（host）

                只输出 JSON，不要任何其他内容：{"channel": "通道id"}

                本轮用户消息：%s"""
                .formatted(registry.buildRoster(), last,
                        recentContext == null || recentContext.isBlank()
                                ? "（无背景，这是开场消息）" : recentContext,
                        text);
        long start = System.currentTimeMillis();
        LlmUsage.set("route"); // 用量观测：路由细判场景标记（仅 L2 模型调用段）
        try {
            String reply = model.chat(prompt);
            String json = stripFences(reply);
            JsonNode node = objectMapper.readTree(json);
            String channel = node.path("channel").asText("");
            if (!channel.isBlank() && registry.get(channel) != null) {
                log.info("路由 L2 判定（channel={}，last={}，耗时 {}ms）：{}", channel, last,
                        System.currentTimeMillis() - start, compact(text));
                return channel;
            }
            // 输出了未知通道：视为不可靠，L3 粘住当前通道
            log.warn("路由 L2 输出未知通道「{}」（L3 粘住 {}，耗时 {}ms）", channel, last,
                    System.currentTimeMillis() - start);
            return last;
        } catch (Exception e) {
            // ---- L3 兜底：分类失败粘住当前通道，不中断对话 ----
            log.warn("路由 L2 失败（L3 粘住 {}，耗时 {}ms）：{}", last,
                    System.currentTimeMillis() - start, e.getMessage());
            return last;
        } finally {
            LlmUsage.clear();
        }
    }

    /** 日志友好：压掉换行、截断长消息 */
    private static String compact(String text) {
        String t = text.replaceAll("\\s+", " ");
        return t.length() <= 60 ? t : t.substring(0, 59) + "…";
    }

    /** 剥掉模型输出可能包裹的 markdown 代码围栏，取裸 JSON 文本 */
    private static String stripFences(String s) {
        String t = s == null ? "" : s.trim();
        if (t.startsWith("```")) {
            int first = t.indexOf('\n');
            int last = t.lastIndexOf("```");
            if (first > 0 && last > first) {
                t = t.substring(first + 1, last).trim();
            }
        }
        return t;
    }
}
