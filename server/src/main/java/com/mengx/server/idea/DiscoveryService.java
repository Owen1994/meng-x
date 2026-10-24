package com.mengx.server.idea;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mengx.server.idea.agent.AgentSpec;
import com.mengx.server.idea.agent.CoCreationTurn;
import com.mengx.server.idea.agent.IdeaAgentFactory;
import com.mengx.server.idea.agent.InterviewPlan;
import com.mengx.server.idea.agent.SpecDraft;
import com.mengx.server.llm.LlmUsage;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 需求澄清服务（human-in-the-loop requirements discovery loop）：
 * 发现模式下每轮由共创推演官（CoCreation）在用户发言当轮同步推演——
 * 一次调用同时维护应用规格快照（九维 + 自定义维度，随轮累积演进）
 * 与本轮议程（聚焦维度、建议问法、建议方案、纯确认短路）。
 * 推演结论随轮以 discovery 状态消息存档（JSON 信封，SSE design 事件当轮直发前端营造图面板）：
 * 注入主对话代理/喂卡片装配器统一经 renderForHost 渲染为文本。
 * 用户全程只感知主对话代理一人——推演官不发言、不露面，转述权完全归前台。
 * 本类只做编排（回灌截取、单官调用与退避重试、存档、渲染）；prompt 与模型调用归 idea.agent 包。
 */
@Service
public class DiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(DiscoveryService.class);

    /**
     * 需求澄清一轮的状态信封（discovery 消息存档原文，前端营造图面板直接消费）。
     * 注意：JSON 键固定为 product/craft，与存量存档兼容，重命名组件不影响落库格式。
     */
    public record DiscoveryState(@JsonProperty("product") InterviewPlan plan,
                                 @JsonProperty("craft") SpecDraft draft) {}

    private final IdeaAgentFactory agents;
    private final IdeaMessageRepository messageRepository;
    private final ObjectMapper counselMapper;

    public DiscoveryService(IdeaAgentFactory agents, IdeaMessageRepository messageRepository) {
        this.agents = agents;
        this.messageRepository = messageRepository;
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.counselMapper = mapper;
    }

    /**
     * 单官同步推演（普通轮约 10~15 秒、纯确认轮 3~5 秒）并立即存档，
     * 返回状态存档原文（JSON 信封：注入主对话经 renderForHost，SSE design 事件直发前端）。
     */
    public String composeAndArchive(Long sessionId, String context) {
        String stored = compose(sessionId, context);
        if (stored != null) {
            archive(sessionId, stored);
        }
        return stored;
    }

    /**
     * 只推演、不落库：返回状态存档原文（JSON 信封；序列化失败降级为渲染文本），两次尝试均失败返回 null。
     * 上一版规格快照回灌推演官，让应用规格书随轮累积演进；
     * 模型异常（含 1302 限流）与输出不完整（spec 缺失）均 2 秒退避后重试一次。
     */
    public String compose(Long sessionId, String context) {
        long startAt = System.currentTimeMillis();
        // 规格快照回灌：截取上一轮状态中的规格段喂给推演官（议程与注入说明不回灌，避免自我循环）
        String prevSpec = specSectionOf(latestState(sessionId));
        String draftText = prevSpec == null ? "（还没有规格快照——第一次推演）" : prevSpec;
        LlmUsage.set("counsel"); // 用量观测：共创推演场景标记（finally 保证标记必清）
        CoCreationTurn turn = null;
        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                if (attempt > 0) {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
                try {
                    CoCreationTurn result = agents.coCreator().reason(draftText, context);
                    if (result != null && result.spec() != null) {
                        turn = result;
                        break;
                    }
                    log.warn("共创推演输出不完整（第 {} 次，spec 缺失）", attempt + 1);
                } catch (Exception e) {
                    log.warn("共创推演调用失败（第 {} 次）：{}", attempt + 1, e.getMessage());
                }
            }
        } finally {
            LlmUsage.clear();
        }
        if (turn == null) {
            log.warn("需求澄清状态生成失败（sessionId={}，单官两次均败，耗时 {}ms）",
                    sessionId, System.currentTimeMillis() - startAt);
            return null;
        }
        boolean confirmed = Boolean.TRUE.equals(turn.confirmed());
        log.info("需求澄清状态生成（sessionId={}，聚焦={}，纯确认={}，spec 含自定义维度 {} 项，耗时 {}ms）",
                sessionId, turn.agenda(), confirmed,
                turn.spec().extraDimensions() == null ? 0 : turn.spec().extraDimensions().size(),
                System.currentTimeMillis() - startAt);
        DiscoveryState state = new DiscoveryState(
                new InterviewPlan(turn.agenda(), turn.missed(), turn.question(), turn.risk(),
                        turn.priority(), turn.decompose(), turn.proposal(), turn.confirmed()),
                new SpecDraft(turn.scale(), turn.approach(), null, null, turn.spec()));
        // 存档 JSON 信封（SSE design 事件直发前端营造图面板）；注入主对话/喂卡片装配器统一走 renderForHost
        try {
            return counselMapper.writeValueAsString(state);
        } catch (Exception e) {
            log.warn("状态序列化失败（降级存渲染文本，前端面板不展示）：{}", e.getMessage());
            return render(state);
        }
    }

    /** 状态存档落库（失败只告警，不影响本轮回复）；role 固定 counsel，与存量数据兼容 */
    public void archive(Long sessionId, String stored) {
        try {
            messageRepository.save(new IdeaMessageEntity(sessionId, "counsel", stored));
        } catch (Exception e) {
            log.warn("设计简报存档失败（不影响本轮回复）：{}", e.getMessage());
        }
    }

    /** 存档 → 注入主对话代理的文本（规格快照 + 本轮推演结论）；旧版纯文本存档原样返回（兼容） */
    public String renderForHost(String stored) {
        DiscoveryState state = parse(stored);
        return state == null ? stored : render(state);
    }

    /** 存档 → 推演官给出的优先级建议（高/中/低）；无存档、无计划或无优先级返回 null */
    public String planPriorityOf(String stored) {
        DiscoveryState state = parse(stored);
        return state == null || state.plan() == null ? null : state.plan().priority();
    }

    /** 状态渲染为注入主对话代理的文本：规格快照 + 本轮推演结论（严禁向用户暴露内部流程字眼） */
    private String render(DiscoveryState state) {
        StringBuilder sb = new StringBuilder("""
                【设计简报（Design Brief）·内部工作文档】仅供你参考吸收，以你自己的口吻与判断自然表达，该采纳的采纳、该保留意见的保留意见；严禁向用户提及内部流程与工作文档：
                """);
        renderSpecInto(sb, state.draft());
        InterviewPlan p = state.plan();
        if (p != null) {
            sb.append("\n【本轮推演结论】（这一轮只推这一处，一次只推进一步；问法仅供参考，用你自己的口吻）\n");
            sb.append("推进：").append(or(p.agenda(), "定位"));
            if (!isBlank(p.missed())) {
                sb.append("；缺什么：").append(p.missed());
            }
            if (!isBlank(p.question())) {
                sb.append("；建议问法：").append(p.question());
            }
            if (!isBlank(p.risk()) && !"无".equals(p.risk().trim())) {
                sb.append("；风险提醒：").append(p.risk());
            }
            if (!isBlank(p.decompose())) {
                sb.append("；第一期聚焦：").append(p.decompose());
            }
            sb.append('\n');
            if (!isBlank(p.proposal())) {
                sb.append("【建议方案】当前维度可转述给客人点头：").append(p.proposal()).append('\n');
            }
            if (Boolean.TRUE.equals(p.confirmed())) {
                sb.append("【纯确认轮】客人本轮发言是纯确认，快照无新增——请先用一句话收格确认，随即在同一句回复里抛出下一维度的问题，不要再追问已确认的内容。\n");
            }
        }
        return sb.toString().trim();
    }

    /** 规格快照段（应用规格书九维 + 自定义维度；旧版功能级三字段存档按原格式渲染）；draft 为空则什么都不画 */
    private void renderSpecInto(StringBuilder sb, SpecDraft draft) {
        if (draft == null) {
            return;
        }
        if (draft.spec() != null) {
            AgentSpec spec = draft.spec();
            sb.append("\n【应用规格书（AgentSpec）·当前规格快照】（这个应用目前画到的样子；「待聊」/「不适用」的格子就是还没聊或不涉及的，客人问进展时可按图口述；本轮快照已随推演更新，营造图面板当轮可见）\n");
            sb.append("定位：").append(or(spec.positioning())).append('\n');
            sb.append("分身：").append(or(spec.persona())).append('\n');
            sb.append("出场：").append(or(spec.entry())).append('\n');
            sb.append("能力：").append(list(spec.capabilities())).append('\n');
            sb.append("当值：").append(list(spec.agents())).append('\n');
            sb.append("数据工具：").append(list(spec.tools())).append('\n');
            sb.append("流程：").append(or(spec.flow())).append('\n');
            sb.append("边界：").append(or(spec.boundaries())).append('\n');
            sb.append("分期：").append(or(spec.phases())).append('\n');
            sb.append("验收：").append(or(spec.acceptance())).append('\n');
            if (spec.extraDimensions() != null) {
                for (AgentSpec.ExtraDimension d : spec.extraDimensions()) {
                    if (d != null && !isBlank(d.name())) {
                        sb.append(d.name()).append("：").append(or(d.content())).append('\n');
                    }
                }
            }
            if (!isBlank(draft.approach())) {
                sb.append("整体思路：").append(draft.approach()).append('\n');
            }
        } else {
            sb.append("\n【规格快照·当前共识】（这个功能目前聊到的落地共识）\n");
            sb.append("实现要点：").append(or(draft.approach())).append('\n');
            sb.append("边界处理：").append(or(draft.boundary())).append('\n');
            sb.append("验收标准：").append(or(draft.acceptance())).append('\n');
        }
    }

    /** 存档 → 规格快照段（应用规格书/共识）回灌推演官；议程与注入说明不回灌，避免干扰推演 */
    private String specSectionOf(String stored) {
        DiscoveryState state = parse(stored);
        if (state != null) {
            StringBuilder sb = new StringBuilder();
            renderSpecInto(sb, state.draft());
            String draft = sb.toString().trim();
            return draft.isEmpty() ? null : draft;
        }
        // 旧版纯文本存档：按段标记截取（新旧标记都认，保证历史存档回灌不断裂）
        if (stored == null || stored.isBlank()) {
            return null;
        }
        int start = stored.indexOf("【应用规格书");
        if (start < 0) {
            start = stored.indexOf("【营造图·当前设计稿】");
        }
        if (start < 0) {
            start = stored.indexOf("【规格快照·当前共识】");
        }
        if (start < 0) {
            start = stored.indexOf("【设计稿·当前共识】");
        }
        if (start < 0) {
            return null;
        }
        int end = stored.indexOf("【本轮推演结论】");
        if (end < start) {
            end = stored.indexOf("【本轮访谈计划】");
        }
        if (end < start) {
            end = stored.indexOf("【本轮议程】");
        }
        return (end > start ? stored.substring(start, end) : stored.substring(start)).trim();
    }

    /** 存档 → 状态信封；非 JSON（旧版纯文本）或解析失败返回 null */
    private DiscoveryState parse(String stored) {
        if (stored == null || stored.isBlank() || !stored.trim().startsWith("{")) {
            return null;
        }
        try {
            return counselMapper.readValue(stored.trim(), DiscoveryState.class);
        } catch (Exception e) {
            log.warn("设计简报存档解析失败（按旧版文本兼容处理）：{}", e.getMessage());
            return null;
        }
    }

    /** 最近一份状态存档原文（JSON 信封或旧版文本）；无存档返回 null */
    private String latestState(Long sessionId) {
        List<IdeaMessageEntity> list = messageRepository.findBySessionIdAndRoleOrderByIdAsc(sessionId, "counsel");
        return list.isEmpty() ? null : list.get(list.size() - 1).getContent();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String or(String s) {
        return isBlank(s) ? "（待聊）" : s;
    }

    private static String or(String s, String fallback) {
        return isBlank(s) ? fallback : s;
    }

    /** 数组项以分号拼接为一句（空数组视为待聊） */
    private static String list(List<String> items) {
        return items == null || items.isEmpty() ? "（待聊）" : String.join("；", items);
    }
}
