package com.mengx.server.idea.agent;

import com.mengx.server.idea.IdeaHostChatMemory;
import com.mengx.server.idea.IdeaMessageRepository;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * idea 域内部 agent 的装配工厂：ChatModel 可用时懒加载同步 AiServices 代理
 * （CoCreation/OpportunityObserver/CardComposer 等）；
 * StreamingChatModel 可用时懒加载主对话代理（HostAgent，TokenStream 流式）。
 * api-key 缺失时对应 Bean 不在容器中，{@link #available()} / {@link #streamingAvailable()}
 * 为 false，调用方按原有路径降级（单官皆败/观察跳过/未配置错误事件）。
 *
 * 主对话代理上下文按 LangChain4j 原生方式托管：@MemoryId（sessionId）+ 每场一个
 * IdeaHostChatMemory（ChatMemoryService 按 id 缓存复用）；窗口唯一真源是业务消息表
 * idea_messages，人设随每轮 @SystemMessage 动态注入实例、不落库，
 * 不再单设快照表、无需播种/镜像（适配 GA 版 memory 以 store 为真源的语义，详见
 * IdeaHostChatMemory 类注释）。
 * 后续 AgentModelCatalog 落地后，此处按 agent 名取各自模型档即可，调用方不变。
 */
@Component
public class IdeaAgentFactory {

    /** 主对话代理窗口容量（条，user/assistant 各算一条）：约 20 轮来回，超出自动滑窗 */
    public static final int HOST_MEMORY_WINDOW_MESSAGES = 40;

    private final ObjectProvider<ChatModel> chatModelProvider;
    private final ObjectProvider<StreamingChatModel> streamingModelProvider;
    private final ChatMemoryProvider chatMemoryProvider;

    private volatile CoCreationAgent coCreator;
    private volatile OpportunityObserverAgent observer;
    private volatile CardComposerAgent composer;
    private volatile HostAgent host;
    private volatile WishDistillerAgent wishDistiller;
    private volatile WishRefinerAgent wishRefiner;
    private volatile PrdFormatterAgent prdFormatter;
    private volatile ThanksAgent thanks;

    public IdeaAgentFactory(ObjectProvider<ChatModel> chatModelProvider,
                            ObjectProvider<StreamingChatModel> streamingModelProvider,
                            IdeaMessageRepository messageRepository) {
        this.chatModelProvider = chatModelProvider;
        this.streamingModelProvider = streamingModelProvider;
        this.chatMemoryProvider = memoryId -> {
            if (!(memoryId instanceof Long sessionId)) {
                throw new IllegalArgumentException(
                        "memoryId 必须是 Long 类型的 sessionId，实际：" + memoryId);
            }
            return new IdeaHostChatMemory(sessionId, messageRepository);
        };
    }

    /** 同步模型是否已配置（未配置 api-key 时内部 agent 整体不可用） */
    public boolean available() {
        return chatModelProvider.getIfAvailable() != null;
    }

    /** 流式模型是否已配置（未配置时主对话主链路直接回未配置错误事件） */
    public boolean streamingAvailable() {
        return streamingModelProvider.getIfAvailable() != null;
    }

    /** 共创推演官：发现模式下每轮同步推演快照与议程 */
    public CoCreationAgent coCreator() {
        CoCreationAgent local = coCreator;
        if (local == null) {
            synchronized (this) {
                local = coCreator;
                if (local == null) {
                    local = AiServices.create(CoCreationAgent.class, requireModel());
                    coCreator = local;
                }
            }
        }
        return local;
    }

    /** 机会观察器：主对话代理收口后旁路判定 PROPOSE/NONE/EXIT */
    public OpportunityObserverAgent observer() {
        OpportunityObserverAgent local = observer;
        if (local == null) {
            synchronized (this) {
                local = observer;
                if (local == null) {
                    local = AiServices.create(OpportunityObserverAgent.class, requireModel());
                    observer = local;
                }
            }
        }
        return local;
    }

    /** 卡片装配器：宣布定稿后异步装配功能卡 JSON */
    public CardComposerAgent composer() {
        CardComposerAgent local = composer;
        if (local == null) {
            synchronized (this) {
                local = composer;
                if (local == null) {
                    local = AiServices.create(CardComposerAgent.class, requireModel());
                    composer = local;
                }
            }
        }
        return local;
    }

    public HostAgent host() {
        HostAgent local = host;
        if (local == null) {
            synchronized (this) {
                local = host;
                if (local == null) {
                    local = AiServices.builder(HostAgent.class)
                            .streamingChatModel(requireStreamingModel())
                            .chatMemoryProvider(chatMemoryProvider)
                            .build();
                    host = local;
                }
            }
        }
        return local;
    }

    public WishDistillerAgent wishDistiller() {
        WishDistillerAgent local = wishDistiller;
        if (local == null) {
            synchronized (this) {
                local = wishDistiller;
                if (local == null) {
                    local = AiServices.create(WishDistillerAgent.class, requireModel());
                    wishDistiller = local;
                }
            }
        }
        return local;
    }

    public WishRefinerAgent wishRefiner() {
        WishRefinerAgent local = wishRefiner;
        if (local == null) {
            synchronized (this) {
                local = wishRefiner;
                if (local == null) {
                    local = AiServices.create(WishRefinerAgent.class, requireModel());
                    wishRefiner = local;
                }
            }
        }
        return local;
    }

    public PrdFormatterAgent prdFormatter() {
        PrdFormatterAgent local = prdFormatter;
        if (local == null) {
            synchronized (this) {
                local = prdFormatter;
                if (local == null) {
                    local = AiServices.create(PrdFormatterAgent.class, requireModel());
                    prdFormatter = local;
                }
            }
        }
        return local;
    }

    public ThanksAgent thanks() {
        ThanksAgent local = thanks;
        if (local == null) {
            synchronized (this) {
                local = thanks;
                if (local == null) {
                    local = AiServices.create(ThanksAgent.class, requireModel());
                    thanks = local;
                }
            }
        }
        return local;
    }

    private ChatModel requireModel() {
        ChatModel model = chatModelProvider.getIfAvailable();
        if (model == null) {
            throw new IllegalStateException("模型未配置（ai.model.api-key 缺失），agent 无法运行");
        }
        return model;
    }

    private StreamingChatModel requireStreamingModel() {
        StreamingChatModel model = streamingModelProvider.getIfAvailable();
        if (model == null) {
            throw new IllegalStateException("流式模型未配置（ai.model.api-key 缺失），仙官无法作答");
        }
        return model;
    }
}
