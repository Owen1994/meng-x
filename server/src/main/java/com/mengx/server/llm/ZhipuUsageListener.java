package com.mengx.server.llm;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import java.util.List;
import java.util.Map;

/**
 * 官方 langchain4j 客户端 → LlmUsage 静态枢纽的适配监听器：
 * 每个模型 Bean 注册一个实例（streaming 标记同步/流式），onRequest 打点计时，
 * onResponse/onError 归一为既有六元组上报，LlmUsageRecorder 落库与指标口径不变。
 * 口径说明：token 真值取响应 usage；智谱流式最后一帧可能不带 usage，
 * 此时沿用旧 ZhipuApi 的字符估算兜底（中文约一字一 token）。
 */
public class ZhipuUsageListener implements ChatModelListener {

    private static final Object START_KEY = new Object();

    private final boolean streaming;

    public ZhipuUsageListener(boolean streaming) {
        this.streaming = streaming;
    }

    @Override
    public void onRequest(ChatModelRequestContext context) {
        context.attributes().put(START_KEY, System.nanoTime());
    }

    @Override
    public void onResponse(ChatModelResponseContext context) {
        long latencyMs = elapsed(context.attributes());
        ChatResponse response = context.chatResponse();
        ChatRequest request = context.chatRequest();
        String model = firstNonBlank(
                response.metadata() == null ? null : response.metadata().modelName(),
                request.parameters() == null ? null : request.parameters().modelName());

        int promptTokens = 0;
        int completionTokens = 0;
        TokenUsage usage = response.metadata() == null ? null : response.metadata().tokenUsage();
        if (usage != null) {
            promptTokens = nz(usage.inputTokenCount());
            completionTokens = nz(usage.outputTokenCount());
        }
        // 流式 usage 缺失（或服务端返回 0）时按字符估算，保持历史统计口径连续
        if (promptTokens <= 0) {
            promptTokens = requestTextLength(request.messages());
        }
        if (completionTokens <= 0 && response.aiMessage() != null) {
            completionTokens = textLen(response.aiMessage().text());
        }

        LlmUsage.report(LlmUsage.sceneOf(model, streaming), model,
                promptTokens, completionTokens, latencyMs, "ok");
    }

    @Override
    public void onError(ChatModelErrorContext context) {
        long latencyMs = elapsed(context.attributes());
        ChatRequest request = context.chatRequest();
        String model = request != null && request.parameters() != null
                ? request.parameters().modelName() : null;
        // 1302 限流单独标记（退避策略与成本分析关心）；官方客户端已做退避重试，
        // 这里看到的是重试耗尽后的终态错误
        Throwable error = context.error();
        String msg = error == null || error.getMessage() == null ? "" : error.getMessage();
        String status = msg.contains("1302") ? "rate_limited" : "error";
        LlmUsage.report(LlmUsage.sceneOf(model, streaming), model, 0, 0, latencyMs, status);
    }

    private static long elapsed(Map<Object, Object> attributes) {
        Object start = attributes.get(START_KEY);
        if (start instanceof Long nano) {
            return (System.nanoTime() - nano) / 1_000_000;
        }
        return 0;
    }

    private static int requestTextLength(List<ChatMessage> messages) {
        if (messages == null) {
            return 0;
        }
        int total = 0;
        for (ChatMessage m : messages) {
            if (m instanceof SystemMessage sm) {
                total += textLen(sm.text());
            } else if (m instanceof UserMessage um) {
                total += textLen(um.singleText());
            } else if (m instanceof AiMessage am) {
                total += textLen(am.text());
            }
        }
        return total;
    }

    private static int textLen(String text) {
        return text == null ? 0 : text.length();
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }
}
