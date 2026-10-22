package com.mengx.server.llm;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.util.List;

/**
 * 智谱流式 StreamingChatModel（langchain4j 接口实现，注入 thinking:disabled）：
 * 前台仙官/法学助手流式对话主链路，与原 OpenAiStreamingChatModel 可互换，
 * 消除 glm-4.7 思考模式导致的 30~40 秒首 token 延迟。
 */
public class ZhipuStreamingChatModel implements StreamingChatModel {

    private final ZhipuApi.ZhipuConfig cfg;

    public ZhipuStreamingChatModel(ZhipuApi.ZhipuConfig cfg) {
        this.cfg = cfg;
    }

    @Override
    public void chat(List<ChatMessage> messages, StreamingChatResponseHandler handler) {
        try {
            String full = ZhipuApi.chatStream(cfg, ZhipuApi.toApiMessages(messages),
                    handler::onPartialResponse);
            handler.onCompleteResponse(ChatResponse.builder()
                    .aiMessage(AiMessage.from(full)).build());
        } catch (RuntimeException e) {
            handler.onError(e);
        }
    }
}
