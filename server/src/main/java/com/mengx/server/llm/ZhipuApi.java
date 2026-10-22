package com.mengx.server.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 智谱 GLM 原生 HTTP 客户端（替换 langchain4j 的 OpenAI 兼容层）：
 * 核心原因是 langchain4j 不支持自定义请求体字段，而 glm-4.7 是思考型模型，
 * 必须显式注入 thinking:{"type":"disabled"} 才能避免 30~40 秒的思考首延迟。
 * 同时内置 1302 限流退避重试（与 AgentRunner 约定一致：2 秒退避、重试一次）。
 */
public final class ZhipuApi {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    /** 模型调用配置（一档一实例） */
    public record ZhipuConfig(
            String baseUrl, String apiKey, String model,
            double temperature, int maxTokens, int timeoutSeconds) {}

    /** 请求消息（role: system/user/assistant） */
    public record Msg(String role, String content) {}

    private ZhipuApi() {}

    /** langchain4j 消息 → 智谱请求消息（项目内只用纯文本消息） */
    public static List<Msg> toApiMessages(List<ChatMessage> messages) {
        List<Msg> out = new ArrayList<>(messages.size());
        for (ChatMessage m : messages) {
            if (m instanceof SystemMessage sm) {
                out.add(new Msg("system", sm.text()));
            } else if (m instanceof UserMessage um) {
                out.add(new Msg("user", um.singleText()));
            } else if (m instanceof AiMessage am && am.text() != null && !am.text().isBlank()) {
                out.add(new Msg("assistant", am.text()));
            }
        }
        return out;
    }

    /** 非流式补全：返回正文（1302/网络失败 2 秒退避重试一次）；每次调用上报用量（成功带 usage 真值） */
    public static String chat(ZhipuConfig cfg, List<Msg> messages) {
        long start = System.currentTimeMillis();
        RuntimeException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            if (attempt > 0 && !sleep()) {
                throw new IllegalStateException("智谱调用被中断");
            }
            try {
                Usage usage = doChat(cfg, messages, false, null);
                reportUsage(cfg, false, usage, System.currentTimeMillis() - start, "ok");
                return usage.text;
            } catch (RuntimeException e) {
                last = e;
            }
        }
        reportUsage(cfg, false, null, System.currentTimeMillis() - start, statusOf(last));
        throw last;
    }

    /** 流式补全：每个正文 token 回调 onDelta，返回累积全文。
     *  连接/首响应失败退避重试一次；已向用户输出过 token 后断流不可重试（避免正文重复），直接抛错。
     *  用量上报：流式 SSE 无 usage 字段，completion 按字符数近似估算（中文约一字一 token） */
    public static String chatStream(ZhipuConfig cfg, List<Msg> messages, Consumer<String> onDelta) {
        long start = System.currentTimeMillis();
        RuntimeException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            if (attempt > 0 && !sleep()) {
                throw new IllegalStateException("智谱调用被中断");
            }
            boolean[] started = {false};
            try {
                Usage usage = doChat(cfg, messages, true, token -> {
                    started[0] = true;
                    onDelta.accept(token);
                });
                reportUsage(cfg, true, usage, System.currentTimeMillis() - start, "ok");
                return usage.text;
            } catch (RuntimeException e) {
                if (started[0]) {
                    reportUsage(cfg, true, null, System.currentTimeMillis() - start, statusOf(e));
                    throw new RuntimeException("流式输出中断（已输出部分正文，不可重试）：" + e.getMessage(), e);
                }
                last = e;
            }
        }
        reportUsage(cfg, true, null, System.currentTimeMillis() - start, statusOf(last));
        throw last;
    }

    /** 单次调用结果（text=正文；usage 字段流式不可得时为估算值） */
    private record Usage(String text, int promptTokens, int completionTokens, boolean estimated) {}

    /** 用量上报：scene 由 ThreadLocal 标记或按模型/流式推断；prompt 按请求字符近似（非流式有真值时覆盖） */
    private static void reportUsage(ZhipuConfig cfg, boolean stream, Usage usage,
            long latencyMs, String status) {
        int promptTokens = usage == null ? 0 : usage.promptTokens();
        int completionTokens = usage == null ? 0 : usage.completionTokens();
        LlmUsage.report(LlmUsage.sceneOf(cfg.model(), stream), cfg.model(),
                promptTokens, completionTokens, latencyMs, status);
    }

    /** 失败状态归一：1302 限流单独标记（退避策略与成本分析关心） */
    private static String statusOf(RuntimeException e) {
        String msg = e == null || e.getMessage() == null ? "" : e.getMessage();
        return msg.contains("1302") ? "rate_limited" : "error";
    }

    private static boolean sleep() {
        try {
            Thread.sleep(2000);
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static Usage doChat(ZhipuConfig cfg, List<Msg> messages, boolean stream, Consumer<String> onDelta) {
        try {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("model", cfg.model());
            body.put("stream", stream);
            body.put("temperature", cfg.temperature());
            body.put("max_tokens", cfg.maxTokens());
            // 关键：关闭思考模式（glm-4.7 默认思考，首延迟 30~40 秒）
            body.putObject("thinking").put("type", "disabled");
            ArrayNode msgs = body.putArray("messages");
            int promptChars = 0;
            for (Msg m : messages) {
                ObjectNode o = msgs.addObject();
                o.put("role", m.role());
                o.put("content", m.content());
                promptChars += m.content() == null ? 0 : m.content().length();
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(stripSlash(cfg.baseUrl()) + "/chat/completions"))
                    .timeout(Duration.ofSeconds(cfg.timeoutSeconds()))
                    .header("Authorization", "Bearer " + cfg.apiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            MAPPER.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();

            if (!stream) {
                HttpResponse<String> resp =
                        CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) {
                    throw new RuntimeException("智谱 HTTP " + resp.statusCode() + "：" + resp.body());
                }
                JsonNode root = MAPPER.readTree(resp.body());
                String content = root.path("choices").path(0).path("message").path("content").asText("");
                JsonNode usage = root.path("usage");
                int pt = usage.path("prompt_tokens").asInt(estimate(promptChars));
                int ct = usage.path("completion_tokens").asInt(estimate(content.length()));
                return new Usage(content, pt, ct, false);
            }

            HttpResponse<InputStream> resp =
                    CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) {
                throw new RuntimeException("智谱 HTTP " + resp.statusCode());
            }
            StringBuilder full = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring(5).trim();
                    if ("[DONE]".equals(data)) {
                        return new Usage(full.toString(), estimate(promptChars), estimate(full.length()), true);
                    }
                    JsonNode delta = MAPPER.readTree(data)
                            .path("choices").path(0).path("delta");
                    String token = delta.path("content").asText("");
                    if (!token.isEmpty()) {
                        full.append(token);
                        if (onDelta != null) {
                            onDelta.accept(token);
                        }
                    }
                }
            }
            return new Usage(full.toString(), estimate(promptChars), estimate(full.length()), true);
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("智谱调用失败：" + e.getMessage(), e);
        }
    }

    /** 字符数 → token 近似（中文约一字一 token；流式响应无 usage 字段时的估算口径） */
    private static int estimate(int chars) {
        return Math.max(0, chars);
    }

    private static String stripSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
