package com.mengx.server.config;

import com.mengx.server.llm.ZhipuApi;
import com.mengx.server.llm.ZhipuChatModel;
import com.mengx.server.llm.ZhipuStreamingChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 智谱 GLM 模型配置（原生 ZhipuApi 客户端，非 langchain4j OpenAI 兼容层）：
 * 核心：glm-4.7 是思考型模型，ZhipuApi 显式注入 thinking:{"type":"disabled"}
 * 消除 30~40 秒思考首延迟（langchain4j 不支持自定义请求体字段，故自研）。
 * 三档分工：
 * 流式档（StreamingChatModel）= 前台仙官/法学助手，角色扮演，4.7-disabled 保指令遵循；
 * 同步档（ChatModel，@Primary）= 内部结构化 agent（双星/提炼器/格式化官），4.7-disabled；
 * 路由档（ChatModel "routeChatModel"）= 意图路由等轻量二分类，glm-4-flash 快速档。
 * api-key 缺失时返回 null（Bean 不注册），应用仍可启动，调用时给出明确错误。
 */
@Configuration
public class AiModelConfig {

    private static final Logger log = LoggerFactory.getLogger(AiModelConfig.class);

    @Value("${ai.model.base-url}")
    private String baseUrl;

    @Value("${ai.model.api-key:}")
    private String apiKey;

    /** 前台仙官/法学助手（流式对话） */
    @Value("${ai.model.host-name:glm-4-flash}")
    private String hostName;

    @Value("${ai.model.host-temperature:0.7}")
    private Double hostTemperature;

    @Value("${ai.model.host-max-tokens:2048}")
    private Integer hostMaxTokens;

    /** 内部结构化 agent（双星/提炼器/格式化官等） */
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

    /** 流式聊天模型（仙官/法学助手对话主链路，thinking 关闭） */
    @Bean
    public StreamingChatModel streamingChatModel() {
        if (!configured()) {
            log.warn("ai.model.api-key 未配置，跳过流式模型初始化（对话接口不可用）");
            return null;
        }
        log.info("初始化流式模型（仙官/法学，thinking 已关闭）: model={}, temperature={}",
                hostName, hostTemperature);
        return new ZhipuStreamingChatModel(new ZhipuApi.ZhipuConfig(
                baseUrl, apiKey, hostName, hostTemperature, hostMaxTokens, timeoutSeconds));
    }

    /** 同步模型（双星/提炼器/格式化官等结构化 agent，thinking 关闭）；@Primary 供无 qualifier 注入 */
    @Bean
    @Primary
    public ChatModel chatModel() {
        if (!configured()) {
            log.warn("ai.model.api-key 未配置，跳过同步模型初始化");
            return null;
        }
        log.info("初始化同步模型（结构化 agent，thinking 已关闭）: model={}, temperature={}",
                agentName, agentTemperature);
        return new ZhipuChatModel(new ZhipuApi.ZhipuConfig(
                baseUrl, apiKey, agentName, agentTemperature, agentMaxTokens, timeoutSeconds));
    }

    /** 路由模型（意图路由二分类，glm-4-flash 快速档） */
    @Bean("routeChatModel")
    public ChatModel routeChatModel() {
        if (!configured()) {
            return null;
        }
        log.info("初始化路由模型（意图分类）: model={}, temperature={}", routeName, routeTemperature);
        return new ZhipuChatModel(new ZhipuApi.ZhipuConfig(
                baseUrl, apiKey, routeName, routeTemperature, routeMaxTokens, timeoutSeconds));
    }
}
