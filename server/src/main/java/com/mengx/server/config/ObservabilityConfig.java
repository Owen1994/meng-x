package com.mengx.server.config;

import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Hooks;

/**
 * 可观测性装配（metrics + tracing，零新增容器）：
 * 指标：/actuator/prometheus（本机 9913 端口，见 application.yml management 段）；
 * 追踪：Brave 桥接全采样，traceId/spanId 随每行日志输出（logging.pattern.level）。
 * 本类只做一件全局开关：开启 micrometer context-propagation 的 Reactor 自动上下文传播——
 * SSE 流式链路 subscribeOn(boundedElastic) 切后台线程后，trace 上下文自动随订阅点带过去，
 * 否则异步线程上的日志丢 traceId，无法与触发它的请求对账。
 */
@Configuration(proxyBeanMethods = false)
public class ObservabilityConfig {

    static {
        Hooks.enableAutomaticContextPropagation();
    }
}
