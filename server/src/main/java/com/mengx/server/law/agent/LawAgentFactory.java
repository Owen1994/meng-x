package com.mengx.server.law.agent;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 法学域 agent 装配工厂：ChatModel 可用时懒加载出题官与判卷官两个 AiServices 代理。
 * api-key 缺失时调用抛 IllegalStateException，由法学通道按原路径兜底提示。
 * 后续 AgentModelCatalog 落地后按 agent 名取模型档。
 */
@Component
public class LawAgentFactory {

    private final ObjectProvider<ChatModel> chatModelProvider;

    private volatile LawQuestionGenAgent questionGen;
    private volatile LawJudgeAgent judge;

    public LawAgentFactory(ObjectProvider<ChatModel> chatModelProvider) {
        this.chatModelProvider = chatModelProvider;
    }

    public LawQuestionGenAgent questionGen() {
        LawQuestionGenAgent local = questionGen;
        if (local == null) {
            synchronized (this) {
                local = questionGen;
                if (local == null) {
                    local = AiServices.create(LawQuestionGenAgent.class, requireModel());
                    questionGen = local;
                }
            }
        }
        return local;
    }

    public LawJudgeAgent judge() {
        LawJudgeAgent local = judge;
        if (local == null) {
            synchronized (this) {
                local = judge;
                if (local == null) {
                    local = AiServices.create(LawJudgeAgent.class, requireModel());
                    judge = local;
                }
            }
        }
        return local;
    }

    private ChatModel requireModel() {
        ChatModel model = chatModelProvider.getIfAvailable();
        if (model == null) {
            throw new IllegalStateException("模型未配置（ai.model.api-key 缺失），法学 agent 无法运行");
        }
        return model;
    }
}
