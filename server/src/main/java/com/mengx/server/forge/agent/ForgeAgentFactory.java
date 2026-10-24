package com.mengx.server.forge.agent;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 工部 agent 装配工厂：ChatModel 可用时懒加载六个 AiServices 代理
 * （需求分析师/架构师/审校官/通用顾问/法学顾问/排版官）。
 * api-key 缺失时调用抛 IllegalStateException，由岗位编排/排版服务按原路径标 FAILED。
 * 岗位编排（岗序、黑板唯一写者、WAIT/BLOCK/REWRITE 状态机）仍在 com.mengx.server.forge 的 Role 类，
 * 本工厂只负责「prompt 进、POJO/文本出」的 LLM 契约。后续 AgentModelCatalog 落地后按 agent 名取模型档。
 */
@Component
public class ForgeAgentFactory {

    private final ObjectProvider<ChatModel> chatModelProvider;

    private volatile AnalystAgent analyst;
    private volatile ArchitectAgent architect;
    private volatile ReviewerAgent reviewer;
    private volatile DefaultAdvisorAgent defaultAdvisor;
    private volatile LegalAdvisorAgent legalAdvisor;
    private volatile ForgeFormatterAgent formatter;

    public ForgeAgentFactory(ObjectProvider<ChatModel> chatModelProvider) {
        this.chatModelProvider = chatModelProvider;
    }

    public AnalystAgent analyst() {
        AnalystAgent local = analyst;
        if (local == null) {
            synchronized (this) {
                local = analyst;
                if (local == null) {
                    local = AiServices.create(AnalystAgent.class, requireModel());
                    analyst = local;
                }
            }
        }
        return local;
    }

    public ArchitectAgent architect() {
        ArchitectAgent local = architect;
        if (local == null) {
            synchronized (this) {
                local = architect;
                if (local == null) {
                    local = AiServices.create(ArchitectAgent.class, requireModel());
                    architect = local;
                }
            }
        }
        return local;
    }

    public ReviewerAgent reviewer() {
        ReviewerAgent local = reviewer;
        if (local == null) {
            synchronized (this) {
                local = reviewer;
                if (local == null) {
                    local = AiServices.create(ReviewerAgent.class, requireModel());
                    reviewer = local;
                }
            }
        }
        return local;
    }

    public DefaultAdvisorAgent defaultAdvisor() {
        DefaultAdvisorAgent local = defaultAdvisor;
        if (local == null) {
            synchronized (this) {
                local = defaultAdvisor;
                if (local == null) {
                    local = AiServices.create(DefaultAdvisorAgent.class, requireModel());
                    defaultAdvisor = local;
                }
            }
        }
        return local;
    }

    public LegalAdvisorAgent legalAdvisor() {
        LegalAdvisorAgent local = legalAdvisor;
        if (local == null) {
            synchronized (this) {
                local = legalAdvisor;
                if (local == null) {
                    local = AiServices.create(LegalAdvisorAgent.class, requireModel());
                    legalAdvisor = local;
                }
            }
        }
        return local;
    }

    public ForgeFormatterAgent formatter() {
        ForgeFormatterAgent local = formatter;
        if (local == null) {
            synchronized (this) {
                local = formatter;
                if (local == null) {
                    local = AiServices.create(ForgeFormatterAgent.class, requireModel());
                    formatter = local;
                }
            }
        }
        return local;
    }

    private ChatModel requireModel() {
        ChatModel model = chatModelProvider.getIfAvailable();
        if (model == null) {
            throw new IllegalStateException("模型未配置（ai.model.api-key 缺失），工部 agent 无法运行");
        }
        return model;
    }
}
