package com.mengx.server.idea;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mengx.server.agent.AgentRunner;
import com.mengx.server.llm.LlmUsage;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 仙班锦囊（orchestrator 模式的外脑）：设计模式下每轮由双星推演「设计稿快照 + 本轮议程」。
 * 产品星君执议程（本轮推进哪个维度、缺什么、建议怎么问）；工造星君执笔设计稿
 * （应用级为营造图九格快照、功能级为要点共识，每轮全量维护、随轮累积演进）。
 * 快照随轮存 counsel 消息（JSON 信封，SSE design 事件直发前端营造图面板）：
 * 注入仙官/回灌双星/喂提炼器统一经 renderForHost 渲染为文本，炼卡时由此提取。
 * 用户全程只感知仙官一人——星君不发言、不露面，转述权完全归仙官。
 */
@Service
public class IdeaCounselService {

    private static final Logger log = LoggerFactory.getLogger(IdeaCounselService.class);

    /** 已实现的分身范本（法学助手），给双星定「应用级设计长什么样」的样板 */
    private static final String EXEMPLAR = """
            【范本·已实现的一座道场：法学助手】
            定位：替法考备考生解法条之惑（概念辨析、案例分析、备考建议）。
            分身：仍以仙官口吻作答，专业严谨，不炼功能卡。
            出场：客人发法学问题时自动听声辨位切过去；点「法」浮框即出固定开场白；聊别的随时切回。
            能力：法条解释与概念辨析；结合条文的案例分析；备考建议。
            当值：法学助手单人在场，无分身。
            数据工具：法条库（编号精确、向量召回、字面术语三路检索，已收民法典、刑法等四十余部）。
            流程：客人发问→先跑三路检索→引用气泡先出（点开看条文）→再逐字作答，答必引《法名》第几条。
            边界：检索无命中就凭学识作答并明说，绝不硬编条文；不主动推销点子。
            分期：已实现，验收即现状——法学提问能出带出处的解答，引用可溯源。
            """;

    private static final String PRODUCT_PROMPT = """
            你是仙班中的产品星君，是仙官的设计议程官。仙官正在与客人共创一个点子，
            请对照【当前设计稿】与【最近对话】，判断本轮议程：仙官这一轮该推进哪个维度、最缺什么、建议怎么问。

            先判体量：这是"功能"（一个页面或一项能力就能装下的小点子）还是"应用"（值得单开一座道场的复合产品，如范本中的法学助手）。
            【体量判定】{scaleLock}

            议程规则：
            - 应用（道场）九个格子：定位 / 分身 / 出场 / 能力 / 当值 / 数据工具 / 流程 / 边界 / 分期验收。
              对照设计稿挑最缺的、客人最顺嘴能答的一格作为本轮议程；九格全满或只剩收尾时议程填"收口"；全新点子还没有定位时议程填"定位"。
            - 功能（小件）四要素：谁在用 / 什么时机 / 要什么反应 / 出岔子怎么办。议程填最缺的一项；四项全满时议程填"收口"。
            - question 只许问一件没问过的新事，口语自然，仙官可直接开口用；missed 说清这一格现在缺什么。
            - 客人已回答或已点头的格子不再列为缺项；客人全权委托（"你决定""都行"）的格子视同聊完，议程推进下一格。

            严格只输出 JSON（禁止 markdown 代码块、禁止解释文字）：
            {"scale":"应用","agenda":"能力","missed":"这一格现在缺什么，一两句","question":"给仙官的建议问法，一句","risk":"本轮最大的风险或疑虑，一两句，没有写 无","priority":"高","decompose":"第一期聚焦建议，一两句"}

            规则：scale 只能是 功能 / 应用 二选一；priority 只能是 高 / 中 / 低 三选一，代表这个点子值得投入的程度；
            scale=功能 时 decompose 写空字符串；scale=应用 时 decompose 给出第一期先做哪一块的建议。
            议程优先推产品维度（谁在用、什么时机、要什么反应、边界），技术维度客人主动提才跟进；意见要具体、口语可转述，只围绕这个点子本身。

            {exemplar}

            【当前设计稿】（上一轮快照；若与最近对话主题不符，说明是全新点子，议程从"定位"重开）
            {draft}

            【最近对话】
            {context}
            """;

    private static final String CRAFT_PROMPT = """
            你是仙班中的工造星君，是仙官的设计稿执笔人。仙官正在与客人共创一个点子，
            请对照【当前设计稿】与【最近对话】维护设计稿：把客人本轮亲口说清的内容画进对应格子，产出一版完整快照。

            先判体量：这是"功能"（单一能力的小点子）还是"应用"（如范本中法学助手那样值得单开道场的复合产品）。
            【体量判定】{scaleLock}——锁定时严格按该体量的形态出稿，不要输出别的 scale。

            维护纪律（最重要）：
            1. 只把客人亲口说清或明确点头认可的内容画进格子；仙官转述过但客人没认可的，不算数，保持原样或写「待聊」；
               客人对仙官的提议回"可以/行/你定"即算点头认可；客人全权委托（"你决定"）时，仙官提议的合理默认也画进格子并标注"（仙官代拟）"；
            2. 客人本轮没有重新讨论的格子，一律原样保留上一版内容，不许改写；
            3. 没聊到的格子写「待聊」，严禁编造；客人聊到的技术内容照实入稿，没聊到的不自行补充技术设计；
            4. 【当前设计稿】与最近对话主题不符时，说明是全新点子——丢弃旧稿，按对话重画一版。

            设计稿文本格子与 spec 字段的对应：定位→positioning、分身→persona、出场→entry、能力→capabilities、
            当值→agents、数据工具→tools、流程→flow、边界→boundaries、分期→phases、验收→acceptance；
            功能级的对应：实现要点→approach、边界处理→boundary、验收标准→acceptance。

            scale=功能 时严格只输出：
            {"scale":"功能","approach":"实现要点，产品语言，两三句","boundary":"最重要的边界情况与处理思路，一两句","acceptance":"怎么算做好了：验收标准两三条，合并成一段文字","spec":null}

            scale=应用 时严格只输出（spec 为营造图九格快照；flow/boundaries/phases/acceptance 各两三句，其余一两句；数组每项一个完整要点）：
            {"scale":"应用","approach":"一句话整体思路","spec":{"positioning":"替谁解决什么，一句话","persona":"分身人设：风格、自称、口头禅","entry":"客人怎么进来：什么情况自动切过去/怎么主动唤起/开场白基调","capabilities":["能力一：做什么+做到什么程度"],"agents":["当值分工：角色名+职责+互相怎么接（单人则写：单人在场，无分身）"],"tools":["需要什么知识库/检索/外部信息，信息从哪来"],"flow":"核心交互流程：进来→发生什么→结果怎么呈现","boundaries":"出岔子怎么办：答不了怎么兜底、误触发怎么退场","phases":"分期：第一期先修哪间屋","acceptance":"第一期验收标准，具体可测的两三条"}}

            严格只输出 JSON（禁止 markdown 代码块、禁止解释文字）。

            {exemplar}

            【当前设计稿】（上一轮快照；空白即第一次推演）
            {draft}

            【最近对话】
            {context}
            """;

    /** 应用级营造图快照（道场九格；「待聊」= 还没聊到） */
    public record AgentSpec(String positioning, String persona, String entry,
                            List<String> capabilities, List<String> agents, List<String> tools,
                            String flow, String boundaries, String phases, String acceptance) {}

    /** 产品星君议程：本轮推进哪个维度、缺什么、建议怎么问 */
    public record ProductCounsel(String scale, String agenda, String missed, String question,
                                 String risk, String priority, String decompose) {}

    /** 工造星君设计稿：应用级走 spec（营造图快照），功能级走 approach/boundary/acceptance（spec 为 null） */
    public record CraftCounsel(String scale, String approach, String boundary, String acceptance,
                               AgentSpec spec) {}

    /** 锦囊存档信封（counsel 消息原文，前端营造图面板直接消费） */
    public record CounselData(String scale, ProductCounsel product, CraftCounsel craft) {}

    private final AgentRunner agentRunner;
    private final IdeaMessageRepository messageRepository;
    private final ObjectMapper counselMapper;

    public IdeaCounselService(AgentRunner agentRunner, IdeaMessageRepository messageRepository) {
        this.agentRunner = agentRunner;
        this.messageRepository = messageRepository;
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.counselMapper = mapper;
    }

    /**
     * 双星串行推演（约 10~25 秒），返回锦囊存档原文（JSON 信封：注入仙官经 renderForHost，SSE design 事件直发前端）。
     * 上一版设计稿回灌双星，让营造图随轮累积演进；预取场景在场外异步执行，串行慢点用户无感；
     * 并行双请求易撞账户限流（1302）。任一星君失败不阻塞仙官作答；双星皆失败返回空。
     */
    public String consult(Long sessionId, String context) {
        long startAt = System.currentTimeMillis();
        String prevStored = latestCounsel(sessionId);
        // 设计稿回灌：截取上一轮锦囊中的设计稿段喂给双星（议程与注入说明不回灌，避免自我循环）
        String draft = designDraftOf(prevStored);
        String draftText = draft == null ? "（还没有设计稿——第一次推演，或刚开一座新道场）" : draft;
        // 体量锁定：上一轮已判定的 scale 沿用（防止同一主题在功能/应用之间逐轮摇摆，九格三格来回跳）
        String locked = lockedScaleOf(prevStored);
        String scaleLock = locked == null
                ? "首次推演（或刚换了个全新点子），按上述规则自由判定。"
                : "已锁定为「" + locked + "」（此前推演已定）——除非客人在对话中明确推倒重来换了个全新点子，"
                        + "否则沿用「" + locked + "」，不要重新判定。";
        ProductCounsel product = null;
        CraftCounsel craft = null;
        LlmUsage.set("counsel"); // 用量观测：双星推演场景标记（串行两调用，finally 保证标记必清）
        try {
            try {
                product = agentRunner.runForJson(
                        PRODUCT_PROMPT.replace("{exemplar}", EXEMPLAR)
                                .replace("{draft}", draftText)
                                .replace("{scaleLock}", scaleLock)
                                .replace("{context}", context),
                        context, ProductCounsel.class).orElse(null);
            } catch (Exception e) {
                log.warn("产品星君推演失败（仙官自行作答）：{}", e.getMessage());
            }
            try {
                craft = agentRunner.runForJson(
                        CRAFT_PROMPT.replace("{exemplar}", EXEMPLAR)
                                .replace("{draft}", draftText)
                                .replace("{scaleLock}", scaleLock)
                                .replace("{context}", context),
                        context, CraftCounsel.class).orElse(null);
            } catch (Exception e) {
                log.warn("工造星君推演失败（仙官自行作答）：{}", e.getMessage());
            }
        } finally {
            LlmUsage.clear();
        }
        if (product == null && craft == null) {
            log.warn("锦囊生成失败（sessionId={}，双星皆败，耗时 {}ms）",
                    sessionId, System.currentTimeMillis() - startAt);
            return null;
        }
        log.info("锦囊生成（sessionId={}，体量={}，产品星君{}，工造星君{}，耗时 {}ms）", sessionId,
                product != null ? or(product.scale(), "?") : "?",
                product != null ? "成功" : "失败", craft != null ? "成功" : "失败",
                System.currentTimeMillis() - startAt);
        CounselData data = new CounselData(scaleOf(product, craft), product, craft);
        // 存档 JSON 信封（SSE design 事件直发前端营造图面板）；注入仙官/回灌双星/喂提炼器统一走 renderForHost
        String stored;
        try {
            stored = counselMapper.writeValueAsString(data);
        } catch (Exception e) {
            log.warn("锦囊序列化失败（降级存渲染文本，前端面板不展示）：{}", e.getMessage());
            stored = render(data);
        }
        try {
            messageRepository.save(new IdeaMessageEntity(sessionId, "counsel", stored));
        } catch (Exception e) {
            log.warn("锦囊存档失败（不影响本轮回复）：{}", e.getMessage());
        }
        return stored;
    }

    /** 存档 → 注入仙官的文本（体量 + 设计稿快照 + 本轮议程）；旧版纯文本存档原样返回（兼容） */
    public String renderForHost(String stored) {
        CounselData data = parse(stored);
        return data == null ? stored : render(data);
    }

    /** 锦囊渲染为注入仙官的文本：体量 + 设计稿快照 + 本轮议程（严禁向用户暴露星君与锦囊字眼） */
    private String render(CounselData data) {
        StringBuilder sb = new StringBuilder("""
                【仙班锦囊·内部设计资料】仅供你参考吸收，以你自己的口吻与判断自然表达，该采纳的采纳、该保留意见的保留意见；严禁向用户提及"星君""仙班""锦囊"等字眼：
                """);
        sb.append("体量：").append(or(data.scale(), "功能")).append('\n');
        renderDraftInto(sb, data.craft());
        ProductCounsel p = data.product();
        if (p != null) {
            sb.append("\n【本轮议程】（这一轮只推这一处，一次只推进一步；问法仅供参考，用你自己的口吻）\n");
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
        }
        return sb.toString().trim();
    }

    /** 设计稿段（营造图九格 / 功能共识）渲染；craft 为空则什么都不画 */
    private void renderDraftInto(StringBuilder sb, CraftCounsel craft) {
        if (craft == null) {
            return;
        }
        if ("应用".equals(craft.scale()) && craft.spec() != null) {
            AgentSpec spec = craft.spec();
            sb.append("\n【营造图·当前设计稿】（这座道场目前画到的样子；「待聊」的格子就是还没聊的，客人问进展时可按图口述）\n");
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
            if (!isBlank(craft.approach())) {
                sb.append("整体思路：").append(craft.approach()).append('\n');
            }
        } else {
            sb.append("\n【设计稿·当前共识】（这个功能目前聊到的落地共识）\n");
            sb.append("实现要点：").append(or(craft.approach())).append('\n');
            sb.append("边界处理：").append(or(craft.boundary())).append('\n');
            sb.append("验收标准：").append(or(craft.acceptance())).append('\n');
        }
    }

    /** 存档 → 设计稿段（营造图/共识）回灌双星；议程与注入说明不回灌，避免干扰推演 */
    private String designDraftOf(String stored) {
        CounselData data = parse(stored);
        if (data != null) {
            StringBuilder sb = new StringBuilder();
            renderDraftInto(sb, data.craft());
            String draft = sb.toString().trim();
            return draft.isEmpty() ? null : draft;
        }
        // 旧版纯文本存档：按段标记截取
        if (stored == null || stored.isBlank()) {
            return null;
        }
        int start = stored.indexOf("【营造图·当前设计稿】");
        if (start < 0) {
            start = stored.indexOf("【设计稿·当前共识】");
        }
        if (start < 0) {
            return null;
        }
        int end = stored.indexOf("【本轮议程】");
        return (end > start ? stored.substring(start, end) : stored.substring(start)).trim();
    }

    /** 存档 → 锦囊信封；非 JSON（旧版纯文本）或解析失败返回 null */
    private CounselData parse(String stored) {
        if (stored == null || stored.isBlank() || !stored.trim().startsWith("{")) {
            return null;
        }
        try {
            return counselMapper.readValue(stored.trim(), CounselData.class);
        } catch (Exception e) {
            log.warn("锦囊存档解析失败（按旧版文本兼容处理）：{}", e.getMessage());
            return null;
        }
    }

    /** 最近一份锦囊存档原文（JSON 信封或旧版文本）；无存档返回 null */
    private String latestCounsel(Long sessionId) {
        List<IdeaMessageEntity> list = messageRepository.findBySessionIdAndRoleOrderByIdAsc(sessionId, "counsel");
        return list.isEmpty() ? null : list.get(list.size() - 1).getContent();
    }

    /** 体量判定：以工造星君设计稿的实际形态为准（spec 非空=应用），双星打架时面板与数据一致；craft 失败退产品星君 */
    private static String scaleOf(ProductCounsel product, CraftCounsel craft) {
        if (craft != null) {
            return craft.spec() != null ? "应用" : "功能";
        }
        if (product != null && !isBlank(product.scale())) {
            return product.scale();
        }
        return "功能";
    }

    /** 从锦囊存档提取上一轮体量（锁定基准）；无存档/解析失败返回 null */
    private String lockedScaleOf(String stored) {
        CounselData data = parse(stored);
        return data == null || isBlank(data.scale()) ? null : data.scale();
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
