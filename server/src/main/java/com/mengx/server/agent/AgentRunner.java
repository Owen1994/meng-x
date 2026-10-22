package com.mengx.server.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Agent 内核：统一的 agent 调用入口（一个 agent = 一段人设 prompt + 一段任务输入）。
 * 多 agent 协作的编排（锦囊、接力炼卡）由业务服务自行组织，参考 IdeaCounselService 双星锦囊。
 */
@Component
public class AgentRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentRunner.class);

    private final ObjectProvider<ChatModel> chatModelProvider;
    private final ObjectMapper objectMapper;

    public AgentRunner(ObjectProvider<ChatModel> chatModelProvider, ObjectMapper objectMapper) {
        this.chatModelProvider = chatModelProvider;
        this.objectMapper = objectMapper;
    }

    /** 同步跑一次 agent，返回回复文本 */
    public String run(String systemPrompt, String userPrompt) {
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            throw new IllegalStateException("模型未配置（ai.model.api-key 缺失），agent 无法运行");
        }
        return chatModel
                .chat(List.of(SystemMessage.from(systemPrompt), UserMessage.from(userPrompt)))
                .aiMessage()
                .text();
    }

    /**
     * 跑 agent 并解析 JSON 输出：先剥 markdown 围栏再截取首个 {…}，失败退避 2 秒重试一次
     * （模型偶尔输出围栏或前缀废话；调用失败多为账户限流 1302，退避错峰能救回大部分）。失败返回 empty。
     */
    public <T> Optional<T> runForJson(String systemPrompt, String userPrompt, Class<T> type) {
        for (int attempt = 0; attempt < 2; attempt++) {
            if (attempt > 0) {
                sleep(2000);
            }
            String reply;
            try {
                reply = run(systemPrompt, userPrompt);
            } catch (Exception e) {
                log.warn("agent 模型调用失败（第 {} 次）：{}", attempt + 1, e.getMessage());
                continue;
            }
            Optional<T> parsed = parseJson(reply, type);
            if (parsed.isPresent()) {
                return parsed;
            }
        }
        return Optional.empty();
    }

    /** 限流/失败后的退避等待，被打断直接放行由上层兜底 */
    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /** 剥围栏 + 截取 {…} 容错解析 */
    private <T> Optional<T> parseJson(String reply, Class<T> type) {
        if (reply == null) {
            return Optional.empty();
        }
        String text = reply.trim();
        if (text.startsWith("```")) {
            int lineEnd = text.indexOf('\n');
            if (lineEnd > 0) {
                text = text.substring(lineEnd + 1);
            }
            if (text.endsWith("```")) {
                text = text.substring(0, text.length() - 3);
            }
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(text.substring(start, end + 1), type));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** 把结果对象序列化为 JSON 文本（评审结果落库等场景） */
    public String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }
}
