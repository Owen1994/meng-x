package com.mengx.server.config;

import com.mengx.server.llm.ZhipuUsageListener;
import dev.langchain4j.community.model.zhipu.ZhipuAiChatModel;
import dev.langchain4j.community.model.zhipu.ZhipuAiStreamingChatModel;
import dev.langchain4j.community.model.zhipu.chat.Thinking;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 智谱 GLM 模型配置（langchain4j 官方社区集成 langchain4j-community-zhipu-ai）：
 * thinking:{"type":"disabled"} 经官方 builder 全局注入，消除 glm-4.7 思考模式
 * 30~40 秒首 token 延迟；该客户端原生支持 tools/function calling，后续 AiServices 可直接挂 @Tool。
 * 三档分工：
 * 流式档（StreamingChatModel）= 前台主对话代理/法学助手，角色扮演，4.7-disabled 保指令遵循；
 * 同步档（ChatModel，@Primary）= 内部结构化 agent（需求澄清双官/观察器/装配器/PRD 格式化器），4.7-disabled；
 * 路由档（ChatModel "routeChatModel"）= 意图路由等轻量二分类，glm-4-flash 快速档。
 * 重试：同步档 maxRetries=2（限流/网络故障退避重试一次）；流式档沿用官方默认
 * （仅首响应失败重试，已输出正文后断流不重试），与项目低并发退避策略一致。
 * api-key 缺失时返回 null（Bean 不注册），应用仍可启动，调用时给出明确错误。
 */
@Configuration
public class AiModelConfig {

    private static final Logger log = LoggerFactory.getLogger(AiModelConfig.class);

    /** 全档统一关闭思考模式（flash 收到该字段亦兼容；与旧自研客户端行为一致） */
    private static final Thinking THINKING_DISABLED = Thinking.builder().type("disabled").build();

    @Value("${ai.model.base-url}")
    private String baseUrl;

    @Value("${ai.model.api-key:}")
    private String apiKey;

    /** 前台主对话代理/法学助手（流式对话） */
    @Value("${ai.model.host-name:glm-4-flash}")
    private String hostName;

    @Value("${ai.model.host-temperature:0.7}")
    private Double hostTemperature;

    @Value("${ai.model.host-max-tokens:2048}")
    private Integer hostMaxTokens;

    /** 内部结构化 agent（需求澄清双官/观察器/装配器/PRD 格式化器等） */
    @Value("${ai.model.agent-name:glm-4-plus}")
    private String agentName;

    @Value("${ai.model.agent-temperature:0.2}")
    private Double agentTemperature;

    @Value("${ai.model.agent-max-tokens:4096}")
    private Integer agentMaxTokens;

    /** 意图路由等轻量分类（glm-4-flash 非思考快速档） */
    @Value("${ai.model.route-name:glm-4-flash}")
    private String routeName;

    @Value("${ai.model.route-temperature:0.1}")
    private Double routeTemperature;

    @Value("${ai.model.route-max-tokens:256}")
    private Integer routeMaxTokens;

    @Value("${ai.model.timeout-seconds:60}")
    private Integer timeoutSeconds;

    private boolean configured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * 官方客户端在 baseUrl 后以相对路径拼接 api/paas/v4/chat/completions，
     * 故 baseUrl 只能是主机根（默认 https://open.bigmodel.cn/）。
     * 归一化：剥掉尾部斜杠与历史配置里写全的 /api/paas/v4 后缀，再补回一个尾斜杠，
     * 兼容 application-local.yml 中旧格式的完整端点 URL。
     */
    private String rootBaseUrl() {
        String url = baseUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        String suffix = "/api/paas/v4";
        if (url.endsWith(suffix)) {
            url = url.substring(0, url.length() - suffix.length());
        }
        return url + "/";
    }

    /** 流式聊天模型（主对话代理/法学助手对话主链路，thinking 关闭） */
    @Bean
    public StreamingChatModel streamingChatModel() {
        if (!configured()) {
            log.warn("ai.model.api-key 未配置，跳过流式模型初始化（对话接口不可用）");
            return null;
        }
        log.info("初始化流式模型（主对话/法学，thinking 已关闭）: model={}, temperature={}",
                hostName, hostTemperature);
        return ZhipuAiStreamingChatModel.builder()
                .baseUrl(rootBaseUrl())
                .apiKey(apiKey)
                .model(hostName)
                .temperature(hostTemperature)
                .maxToken(hostMaxTokens)
                .thinking(THINKING_DISABLED)
                .callTimeout(Duration.ofSeconds(timeoutSeconds))
                .connectTimeout(Duration.ofSeconds(15))
                .listeners(List.of(new ZhipuUsageListener(true)))
                .build();
    }

    /** 同步模型（需求澄清双官/观察器/装配器/PRD 格式化器等结构化 agent，thinking 关闭）；@Primary 供无 qualifier 注入 */
    @Bean
    @Primary
    public ChatModel chatModel() {
        if (!configured()) {
            log.warn("ai.model.api-key 未配置，跳过同步模型初始化");
            return null;
        }
        log.info("初始化同步模型（结构化 agent，thinking 已关闭）: model={}, temperature={}",
                agentName, agentTemperature);
        return ZhipuAiChatModel.builder()
                .baseUrl(rootBaseUrl())
                .apiKey(apiKey)
                .model(agentName)
                .temperature(agentTemperature)
                .maxToken(agentMaxTokens)
                .thinking(THINKING_DISABLED)
                .callTimeout(Duration.ofSeconds(timeoutSeconds))
                .connectTimeout(Duration.ofSeconds(15))
                .maxRetries(2)
                .listeners(List.of(new ZhipuUsageListener(false)))
                .build();
    }

    /** 路由模型（意图路由二分类，glm-4-flash 快速档） */
    @Bean("routeChatModel")
    public ChatModel routeChatModel() {
        if (!configured()) {
            return null;
        }
        log.info("初始化路由模型（意图分类）: model={}, temperature={}", routeName, routeTemperature);
        return ZhipuAiChatModel.builder()
                .baseUrl(rootBaseUrl())
                .apiKey(apiKey)
                .model(routeName)
                .temperature(routeTemperature)
                .maxToken(routeMaxTokens)
                .thinking(THINKING_DISABLED)
                .callTimeout(Duration.ofSeconds(timeoutSeconds))
                .connectTimeout(Duration.ofSeconds(15))
                .maxRetries(2)
                .listeners(List.of(new ZhipuUsageListener(false)))
                .build();
    }
}
