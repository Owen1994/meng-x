package com.mengx.server.llm;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.List;

/**
 * 智谱同步 ChatModel（langchain4j 接口实现，注入 thinking:disabled）：
 * 供非流式调用方（内部结构化 agent、意图路由等），与原 OpenAiChatModel 可互换。
 */
public class ZhipuChatModel implements ChatModel {

    private final ZhipuApi.ZhipuConfig cfg;

    public ZhipuChatModel(ZhipuApi.ZhipuConfig cfg) {
        this.cfg = cfg;
    }

    @Override
    public String chat(String userMessage) {
        return ZhipuApi.chat(cfg, List.of(new ZhipuApi.Msg("user", userMessage)));
    }

    @Override
    public ChatResponse chat(List<ChatMessage> messages) {
        String text = ZhipuApi.chat(cfg, ZhipuApi.toApiMessages(messages));
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
    }
}
