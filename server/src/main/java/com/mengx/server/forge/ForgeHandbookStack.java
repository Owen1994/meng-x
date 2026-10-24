package com.mengx.server.forge;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 架构手册 STACK 段提取器（方案 §6.3 架构师工具）：手册是全局约束的唯一真源，
 * 「必须复用不得另建」的约束清单从 classpath:forge-handbook.md 的 STACK 标记段提取，
 * 改手册即生效（单一真源防腐烂）。提取模式与 IdeaPrdService 同款（读取逻辑各自保有一份，
 * 保底文本同源同维护）；手册缺失/标记异常回退内置保底文本并告警。
 */
@Component
public class ForgeHandbookStack {

    private static final Logger log = LoggerFactory.getLogger(ForgeHandbookStack.class);

    private static final String HANDBOOK_PATH = "/forge-handbook.md";
    private static final String STACK_BEGIN = "<!-- STACK:BEGIN -->";
    private static final String STACK_END = "<!-- STACK:END -->";

    /** 手册缺失/标记异常时的保底文本（与 IdeaPrdService 内置版保持一致） */
    private static final String FALLBACK_STACK = """
            现有技术栈（施工必须贴合，不得另起炉灶）：
            - 前端：React 18 + Vite 6 单页应用，无路由库，手机端为主场景（视口自适应），PC 端窄栏居中
            - 后端：Java 17 + Spring Boot 3.4，Spring Data JPA（ddl-auto=update），REST 接口 JWT 鉴权，SSE 流式输出
            - 数据库：PostgreSQL + pgvector（向量检索）
            - LLM：智谱 GLM（langchain4j 官方集成，AiServices 多 agent 编排 + ChatMemory 托管上下文）
            - 已有能力体系（涉及以下能力时必须复用现有实现，不得另建）：
              手机号登录即注册、会话与消息持久化（换设备不丢）、主对话代理多 agent 访谈（意图分层路由 / 需求澄清共创 / 规格定稿）、
              功能卡看板流转与文档产出、法学助手（法条 / 真题 RAG 三路检索、法考刷题与学情画像、主观题批改）、
              每日一签、修为墙、LLM 用量埋点与 Prometheus 指标""";

    private volatile String stack = FALLBACK_STACK;

    @PostConstruct
    void loadStackFromHandbook() {
        try (var in = getClass().getResourceAsStream(HANDBOOK_PATH)) {
            if (in == null) {
                log.warn("架构手册缺失（{}），工部架构师使用内置技术栈文本", HANDBOOK_PATH);
                return;
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            int begin = text.indexOf(STACK_BEGIN);
            int end = text.indexOf(STACK_END);
            if (begin < 0 || end < 0 || end <= begin) {
                log.warn("架构手册未找到 STACK 标记段，工部架构师使用内置技术栈文本");
                return;
            }
            String extracted = text.substring(begin + STACK_BEGIN.length(), end).trim();
            if (extracted.isBlank()) {
                log.warn("架构手册 STACK 标记段为空，工部架构师使用内置技术栈文本");
                return;
            }
            this.stack = extracted;
        } catch (Exception e) {
            log.warn("读取架构手册失败（工部架构师使用内置技术栈文本）：{}", e.getMessage());
        }
    }

    /** 技术栈与已有能力体系清单（注入架构师 prompt） */
    public String stack() {
        return stack;
    }
}
