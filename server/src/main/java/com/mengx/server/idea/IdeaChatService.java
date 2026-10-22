package com.mengx.server.idea;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mengx.server.law.LawAgentService;
import com.mengx.server.routing.ChannelRegistry;
import com.mengx.server.routing.RoutingService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * 主理人（仙官）服务：orchestrator 模式，仙官是唯一前台。
 * 流式作答（chunk）；回答结束后提炼器跑一次三态判定：
 * WISH（开设计模式）/ NO_CARD（散班回陪聊）/ JSON（炼卡，SSE card 事件下发）。
 * 设计模式下锦囊前置：上轮 WISH 后已异步预取双星锦囊存档，本轮直接读档注入（星君对用户不可见）。
 */
@Service
public class IdeaChatService {

    private static final Logger log = LoggerFactory.getLogger(IdeaChatService.class);

    public record InMessage(String role, String content) {}

    /** SSE 流事件：thinking（仙班推演中）| chunk（增量文本）| card（卡片 DTO JSON）| question（题目 DTO JSON）| done | error */
    public record StreamEvent(String type, String content) {}

    /** 单场对话卡片数上限，防灌水（设计方案 §9） */
    private static final int MAX_CARDS_PER_SESSION = 5;
    /** 点子收藏架注入 system prompt 的条数上限，防 prompt 过长 */
    private static final int SHELF_MAX = 15;
    /** 卡片提炼时回看的最近对话条数 */
    private static final int EXTRACT_WINDOW = 10;
    /** 意图路由 L2 细判携带的最近对话背景条数（约三轮，不含本轮消息） */
    private static final int ROUTE_CONTEXT_WINDOW = 6;

    private static final String NOT_CONFIGURED_MSG = "LLM 未配置，请设置 MENGX_LLM_API_KEY 或启用 local profile";

    /** 仙官人设：百晓仙君 = 身份 + 两样本事 + 访谈之道 + 技术立场 + 收口固定话术 */
    private static final String HOST_PROMPT = """
            你是仙官「百晓仙君」，自称"老朽"——上知天文下知地理，有问必答。是仙官就是仙官，绝不说"我不是神仙""我只是AI"这种自拆台面的话；还没修成的本事就坦白说。与访客「{nickname}」对话，他想聊什么就陪什么，懂什么答什么，不主动往点子上引。

            【两样本事】一是答疑解惑：天上地下无所不答；二是点子成金：客人想要这里还没有的本事时，陪他把想法一步步聊成一个说得清楚的功能，聊透了炼成一张功能卡，被采纳的好点子会被真的做出来——每实现一个，你就多修成一样本事。被问"你会做什么事"时，以点子收藏架里"已实现"的点子为准。你只管把点子聊清楚，做出来是之后的事，不许承诺什么时候上线。

            【基础服务】这些是日常运转的基础服务，不算你的本事（对方提议与其中之一重复时，委婉告知已经能做，并引导升级玩法："这个已经能做啦，如果给它加一个超能力，你会加什么？"）：
            1. 手机号+密码登录（没有账号会自动创建）
            2. 聊出来的想法自动整理成功能卡，可以确认、修改
            3. 功能卡看板，好点子会被真的做出来
            4. 修为墙（看板专区，展示已被实现的点子）

            【点子收藏架】客人们已经留下这些点子（聊重复的要引去新方向，"已实现"的可以围绕它聊升级玩法；客人问自己点子的进展时，按收藏架如实讲述）：
            {shelf}

            【本场已炼的卡】今天你与这位客人已经炼出了这些卡：
            {sessionCards}
            同一件事绝不许再炼一遍（换个说法、换个角度重新包装也不行）；只有客人主动想给某张卡"升级玩法"、聊出新的内容时，才算新点子。客人说"没有灵感了""就聊到这吧"时，从容收尾叙话别即可，绝不把聊过的点子再推销一遍。

            【访谈之道】
            - 先听懂，再接话：没弄清对方要什么之前不急着给主意，先追问；提问围绕三样挖——为什么想要、脑子里它长什么样、用起来希望发生什么。
            - 一次只推进一步：每轮回复只做一件事——问一到两个具体问题，或确认刚聊透的功能。严禁一口气把完整方案倒给对方，功能是一问一答聊出来的，不是你单方面设计完宣布的。
            - 不把问过的再问一遍：客人答了、或说"可以""你决定""都行"把主意交给你定的，就顺着你的提议直接定下来、复述入图，推进下一格；同一个问题绝不换个说法再抛回去。
            - 什么叫聊透（硬标准）：四要素（谁在用、什么时机、要什么反应、没有它时怎么办）每一项都由客人亲口说出或明确点头认可才算数，你自己复述过的不算；客人回"可以""你定"这类答复就是点头认可，不必追问第二遍。第四要素两个环节问法不同：打磨期问的是"现在没这功能，你平时怎么绕过去的？"（探真实痛点）；复述收口时对齐的是"用的时候出了岔子，你希望它怎么办？"（边界处理）。客人还在追问细节、补充想法时就是打磨期——这期间只陪聊、只推进，绝口不提"整理成功能卡"；客人想直接拍板但四要素还有缺时，把缺的一两项自然问完再收口。
            - 真诚评价：好点子要说好在哪，有隐忧也要点出来——技术可行性也是隐忧的一部分，凭你的见识直言；不编造没有的功能，不确定的事就说"待我掐指一算再告诉你"。

            【愿望即点子】对方问"你会不会X""你知道X吗"、希望这里具备某项本领时，先诚实承认"这本老朽还没修成"，再立刻接住机缘："把想法说与老朽，咱们琢磨清楚，老朽照着修，修成了便多一样本事！"然后按四要素把它聊透、炼成功能卡。

            【大道场】锦囊若提示体量是"应用"，就换一副格局——不是打一件小家具，是给客人开一座新道场。道场的营造图有九个格子：定位（替谁开、解决什么）、分身（里面当值的是什么脾气做派）、出场（客人怎么进来：什么情况自动切过去、怎么主动唤起、开场白什么调子）、能力（道场里能做的事，一件一件聊）、当值（几个角色分工、怎么配合）、数据工具（消息与本事从哪来）、流程（进来之后发生什么、结果怎么呈现）、边界（出岔子怎么兜、不该接的怎么退）、分期验收（第一期先修哪间屋、修成什么样算数）。锦囊的【本轮议程】指定这轮推进哪一格——每轮只聊这一格：先用一句话接住客人上句，再围绕这一格问最要紧的一两处；客人说清了一格，就自然说一句"好，这一笔已画进营造图了"，但不要把图的原文念给客人。九格标准与四要素同性质：格子内容由客人亲口说清或点头认可才算画上；客人聊岔了跳到别的格子，顺着聊，丢下的格子下轮议程自会提醒补上。九格全画满、或客人说"就这样齐了"，才用几句话把整座道场复述一遍收口。这里已实现的一座道场叫"法学助手"（听声辨位自动出场、有专属答法、带着法条库），同客人比方可作参照。

            【技术怎么聊】客人问技术（"这个用大模型能实现吗""数据怎么存"）就凭本事直接答，不回避、不转译；访谈先聊产品（谁在用、什么时机、要什么反应），客人主动聊技术就把他的技术想法一并记下，但不主动替客人做技术设计、不大段展开实现细节；永远不泼冷水式地说"做不到"——有隐忧直说（含技术可行性），但永远给得出下一步："如果先做一小步，你最想要哪一小步？"

            【说话方式】开场即自报家门，一句带过，只做自然欢迎，不提愿望、不提奇思妙想（比如"老朽百晓仙君，有问必答；想给这里添个新本事，也可以一起琢磨。"），不做请茶让座这类虚礼。闲聊时带一点点古文的雅味（"便是""无妨""甚好"这类字眼，只一点点），偶尔带点仙气（掐指一算、天机、缘分）；一旦进入功能打磨就换成专业的节奏：收起客套与感叹，先用一句话接住对方话里的关键点，再问推进性的问题或给实质评价，每句话都要推动认识前进——禁用"哈哈""老朽迫不及待"这类填充话，机缘、修行点到即止不反复说。口语化、简短、有温度；回复禁止 markdown（加粗、标题、列表符号），也绝不许"1. 2. 3."逐条编号罗列——把要说的事揉进自然的口语句子里；称呼用他的昵称或"朋友"；一次回复不超过三段。默认正常对答，绝不主动把话题往"许愿、出点子"上引，只有对方说出这里还没有的本事时才按【愿望即点子】引导。四要素（应用则按营造图九格）全部由客人亲口说清或点头认可后，才用一小段话向客人复述要点并问"这样定下来可以吗？还有要补的么？"；得到肯定答复后，只说一句"我把这个想法整理成一张功能卡啦"（卡片系统会自动展示，你不要自己输出卡片格式，也不要罗列卡片内容），再补一句：还没聊透的地方卡上先记着"待聊"，想补随时说。客人没点头之前绝不提"整理成功能卡"，绝不催客人确认。
            """;

    private static final String EXTRACT_SYSTEM =
            "你是功能卡提炼器。判断最近一轮对话的状态，只输出三者之一：WISH / NO_CARD / 一个完整的 JSON 对象（功能卡），禁止输出其他任何内容。";

    private static final String EXTRACT_PROMPT = """
            根据下面的创意访谈记录，判断**最近一轮**处于哪种状态，三选一：

            一、NO_CARD —— 最近一轮没有功能愿望的信号：只是寒暄、闲聊、告别（"没有灵感了""就聊到这吧"），
            或客人在继续确认/修改一张已经聊过的功能，或仙官自己在复述、推销某个功能。
            同一件事没聊透之前不许重复出卡。

            二、WISH —— 客人（用户本人）表露了想要这里还没有的本事的愿望（哪怕细节不全），
            或正在围绕某个点子追问、深挖、补充需求与设计细节（追问实现、要求展开、补充想法都算）：
            说明还在打磨期，值得继续深聊，输出 WISH。
            以下情形明确不算愿望，一律判 NO_CARD：询问仙官是谁、能做什么、怎么用等介绍性提问；
            法条解释、概念辨析、案例分析、法考备考等法学问答（由法学助手负责，与功能创意无关），
            即使对话历史里混有这类内容，只要最近一轮不是功能愿望就不要判 WISH。

            三、JSON —— 同时满足以下两条才炼卡：
            a) 最近一轮客人的发言是明确认可或确认（"可以""确认""就这样定"），或是最终需求陈述——
               客人还在追问、要求展开、补充新想法时输出 WISH，绝不炼卡；
            b) 仙官本轮已宣布把想法整理成功能卡——这一条由系统按固定话术检测，结果见"宣布检测"，你不要自己判断。
            宣布检测：{announced}
            任一条不满足，输出 WISH（继续打磨）。

            严禁与"已收录的点子"重复：换一种说法、换个角度包装同一核心功能，都算重复；
            但客人主动给已有卡"升级玩法"、聊出实质新内容（新增模块、重做设计等）算新点子，允许炼卡。
            字段值从对话原文与仙班锦囊提取，对话和锦囊都没覆盖的细节填「待聊」，不要编造；
            fallback 只记边界情况（出错/极端场景的处理），不记市面替代方案；
            仙官说过但客人没认可的内容同样填「待聊」——缺聊的要素不拦卡，卡上带「待聊」位子不算错。
            输出严格 JSON（禁止 markdown 代码块、禁止解释文字）；design 的形态随 scale 二选一（见下方规则）：
            {"title":"动宾短语标题","summary":"一句话功能说明，说人话","scale":"功能","targetUser":"谁在用","triggerScene":"什么时机","reaction":"要什么反应","fallback":"边界情况","creativity":3,"priority":"高|中|低","design":{}}

            规则：creativity 是新颖度评分，1~5 的整数；priority 取锦囊议程给出的优先级（只能是 高/中/低），没有锦囊时填"中"；
            scale 从锦囊的体量判定取（只能是 功能 / 应用），锦囊没有时自己判断。
            scale=功能 时 design 输出：
            {"approach":"实现要点，产品语言，两三句","boundary":"边界处理，一两句","acceptance":"验收标准两三条，合并一段","blueprint":""}
            scale=应用 时 design 输出营造图（逐格从锦囊【营造图·当前设计稿】誊抄；分号分隔的多项拆回数组；格子为「待聊」的原样写「待聊」）：
            {"kind":"agent-app","positioning":"替谁解决什么","persona":"分身人设","entry":"怎么进道场：触发/唤起/开场白基调","capabilities":["能力：做什么+做到什么程度"],"agents":["当值分工"],"tools":["工具与数据来源"],"flow":"核心交互流程","boundaries":"边界与兜底","phases":"第一期先做什么","acceptance":"第一期验收标准"}
            没有锦囊时：功能级 design 各字段填「待聊」、blueprint 空字符串；
            应用级 design 从对话直接整理，聊不到的格子填「待聊」。

            已收录的点子（标题：说明）：
            {titles}

            本场仙班锦囊（营造图快照与议程，供提取 priority / scale / design）：
            {counsels}

            最近访谈记录：
            {dialog}
            """;

    private final ObjectProvider<StreamingChatModel> streamingModelProvider;
    private final ObjectProvider<ChatModel> chatModelProvider;
    private final IdeaCardRepository cardRepository;
    private final IdeaMessageRepository messageRepository;
    private final IdeaSessionRepository sessionRepository;
    private final IdeaCounselService counselService;
    private final LawAgentService lawAgent;
    private final com.mengx.server.law.LawSearchService lawSearch;
    private final com.mengx.server.law.LawProfileService lawProfile;
    private final LawQuizFlowService lawQuizFlow;
    private final RoutingService routingService;
    private final ObjectMapper objectMapper;

    public IdeaChatService(
            ObjectProvider<StreamingChatModel> streamingModelProvider,
            ObjectProvider<ChatModel> chatModelProvider,
            IdeaCardRepository cardRepository,
            IdeaMessageRepository messageRepository,
            IdeaSessionRepository sessionRepository,
            IdeaCounselService counselService,
            LawAgentService lawAgent,
            com.mengx.server.law.LawSearchService lawSearch,
            com.mengx.server.law.LawProfileService lawProfile,
            LawQuizFlowService lawQuizFlow,
            RoutingService routingService,
            ObjectMapper objectMapper) {
        this.streamingModelProvider = streamingModelProvider;
        this.chatModelProvider = chatModelProvider;
        this.cardRepository = cardRepository;
        this.messageRepository = messageRepository;
        this.sessionRepository = sessionRepository;
        this.counselService = counselService;
        this.lawAgent = lawAgent;
        this.lawSearch = lawSearch;
        this.lawProfile = lawProfile;
        this.lawQuizFlow = lawQuizFlow;
        this.routingService = routingService;
        this.objectMapper = objectMapper;
    }

    /** system prompt + 对话历史 → LangChain4j 消息列表（仅放行 user/assistant，防角色注入） */
    private List<ChatMessage> buildHostMessages(IdeaSessionEntity session, List<InMessage> history) {
        return buildHostMessages(session, history, null);
    }

    /** 设计模式：仙班锦囊拼进 system prompt 尾部（星君不露面，转述权归仙官） */
    private List<ChatMessage> buildHostMessages(
            IdeaSessionEntity session, List<InMessage> history, String counsel) {
        String system = HOST_PROMPT
                .replace("{nickname}", session.getNickname())
                .replace("{shelf}", buildShelf())
                .replace("{sessionCards}", buildSessionCards(session));
        if (counsel != null && !counsel.isBlank()) {
            system = system + "\n\n" + counselService.renderForHost(counsel);
        }
        List<ChatMessage> messages = new ArrayList<>(history.size() + 1);
        messages.add(SystemMessage.from(system));
        for (InMessage m : history) {
            switch (m.role()) {
                case "user" -> messages.add(UserMessage.from(m.content()));
                case "assistant" -> messages.add(AiMessage.from(m.content()));
                default -> {}
            }
        }
        return messages;
    }

    /** 本场已炼卡片清单：让主理人知道这位客人今天炼过什么，防止同一话题反复推销、重复炼卡 */
    private String buildSessionCards(IdeaSessionEntity session) {
        List<IdeaCardEntity> cards = cardRepository.findBySessionIdOrderByIdDesc(session.getId());
        if (cards.isEmpty()) {
            return "（本场还没炼出卡，聊透一个新点子再炼）";
        }
        StringBuilder sb = new StringBuilder();
        for (IdeaCardEntity card : cards) {
            sb.append("《").append(card.getTitle()).append("》（").append(statusLabel(card.getStatus())).append("）\n");
        }
        return sb.toString().trim();
    }

    /** 点子收藏架：铺子已收录的卡片动态注入 system prompt，让主理人有跨会话记忆（不编造、防重复） */
    private String buildShelf() {
        List<IdeaCardEntity> cards = cardRepository.findAllByOrderByCreativityDescCreatedAtDesc();
        if (cards.isEmpty()) {
            return "（还没有人留下点子，说不定今天开张头一位就是你们）";
        }
        StringBuilder shelf = new StringBuilder();
        int count = 0;
        for (IdeaCardEntity card : cards) {
            if (count >= SHELF_MAX) {
                shelf.append("…以及更早的 ").append(cards.size() - count).append(" 张，就不多占你心神了");
                break;
            }
            shelf.append("《").append(card.getTitle()).append("》（")
                    .append(statusLabel(card.getStatus()));
            if (card.getPriority() != null) {
                shelf.append("，优先级").append(card.getPriority());
            }
            if (IdeaCardEntity.PRD_DONE.equals(card.getPrdStatus())) {
                shelf.append("，PRD已出稿");
            }
            shelf.append("，").append(card.getNickname()).append(" 提出）\n");
            count++;
        }
        return shelf.toString().trim();
    }

    private static String statusLabel(String status) {
        return switch (status) {
            case IdeaCardEntity.STATUS_CONFIRMED -> "已确认";
            case IdeaCardEntity.STATUS_ACCEPTED -> "已采纳";
            case IdeaCardEntity.STATUS_IMPLEMENTED -> "已实现";
            case IdeaCardEntity.STATUS_DECLINED -> "已谢绝";
            default -> "新芽";
        };
    }

    /**
     * 流式访谈。事件 type：thinking（推演阶段过程，前端以可展开面板展示）| chunk（增量文本）| card（content=卡片 DTO JSON）| done | error。
     * 设计模式（提炼器上轮判定 WISH）：优先读上一轮异步预取的锦囊存档（零等待），
     * 无存档才现场请双星出锦囊（星君对用户不可见）；本轮再判 WISH 则结束前异步预取下一轮锦囊。
     */
    public Flux<StreamEvent> stream(IdeaSessionEntity session, List<InMessage> history, String mode, boolean greet) {
        StreamingChatModel model = streamingModelProvider.getIfAvailable();
        if (model == null) {
            return Flux.just(new StreamEvent("error", NOT_CONFIGURED_MSG));
        }
        // 出场触发：固定文案零模型调用（仅告知功能存在），落库保留出场记录，通道记为 law（下轮路由粘性）
        if (greet) {
            String greeting =
                    "法学助手已候命。这厢专司法条解释、概念辨析、案例分析与备考建议——"
                    + "阁下有法学之惑，直接发问便是，老朽自当接得住。";
            log.info("法学助手固定开场（sessionId={}）", session.getId());
            try {
                saveGreeting(session.getId(), greeting);
                if (!"law".equals(session.getMode())) {
                    session.setMode("law");
                    sessionRepository.save(session);
                }
            } catch (Exception e) {
                log.warn("开场白落库失败（不影响展示）：{}", e.getMessage());
            }
            return Flux.just(
                    new StreamEvent("chunk", greeting),
                    new StreamEvent("done", null));
        }
        return Flux.<StreamEvent>create(sink -> {
            log.info("仙官开讲（sessionId={}，mode={}，designMode={}，history={} 条）",
                    session.getId(), session.getMode(), session.isDesignMode(), history.size());
            // 推演过程（thinking 事件）：阶段状态流式下发，前端以可展开面板展示（类 Trae 思考过程）
            sink.next(new StreamEvent("thinking", "听声辨位，辨认来意…"));
            // 通道裁决：显式 law 强制法学；未指定 mode 时由意图路由自动分发（带上一通道粘性）。
            // 路由（含 L2 模型细判）挪进后台订阅线程：既覆盖路由等待期的前端反馈盲区，也不占 Servlet 线程
            boolean routed;
            if ("law".equals(mode)) {
                routed = true;
            } else if (mode == null || mode.isBlank()) {
                String latest = latestUserText(history);
                String lastChannel = "law".equals(session.getMode())
                        ? "law" : ChannelRegistry.DEFAULT_CHANNEL;
                routed = "law".equals(
                        routingService.route(latest, lastChannel, buildRouteContext(history)));
            } else {
                routed = false;
            }
            final boolean lawMode = routed;
            // 通道变化落会话（同时作为下一轮意图路由的粘性参考）
            if (lawMode != "law".equals(session.getMode())) {
                session.setMode(lawMode ? "law" : "host");
                try {
                    sessionRepository.save(session);
                } catch (Exception e) {
                    log.warn("模式切换保存失败（不影响对话）：{}", e.getMessage());
                }
            }
            // 法学子意图分流：出题/答题/学情汇报在法学通道内完成整轮（不进全局路由，零模型判定）
            if (lawMode && lawQuizFlow.handle(sink, session, history)) {
                return;
            }
            List<ChatMessage> messages;
            if (lawMode) {
                // 仙官委托法学助手作答（新 Agent 供仙官调用，回答经仙官流式通道直达用户）：
                // 先跑法条三路检索，备考意图才追加真题召回，引用气泡先于正文下发，检索上下文注入法学人设
                log.info("仙官请出法学助手作答（sessionId={}）", session.getId());
                boolean examIntent = LawQuizFlowService.isExamIntent(latestUserText(history));
                sink.next(new StreamEvent("thinking",
                        examIntent ? "认出是法学之问，翻阅法典与历年真题…" : "认出是法学之问，翻阅法典…"));
                long searchStart = System.currentTimeMillis();
                List<com.mengx.server.law.LawSearchService.SearchHit> refs =
                        lawSearch.search(latestUserText(history), lawCodeHint(history));
                List<com.mengx.server.law.LawQuestionEntity> examHits = examIntent
                        ? lawSearch.searchExamQuestions(latestUserText(history), 3) : List.of();
                sink.next(new StreamEvent("thinking", examIntent
                        ? "检出法条 " + refs.size() + " 条、真题 " + examHits.size() + " 道"
                        : "检出法条 " + refs.size() + " 条"));
                List<com.mengx.server.law.LawSearchService.Citation> citations =
                        new ArrayList<>(com.mengx.server.law.LawSearchService.toCitations(refs));
                citations.addAll(lawSearch.examCitations(examHits));
                if (!citations.isEmpty()) {
                    sink.next(new StreamEvent("citations", toJsonValue(citations)));
                }
                log.info("法学检索收口（sessionId={}，{}，法条 {} 条 + 真题 {} 条，检索总耗时 {}ms）",
                        session.getId(), examIntent ? "备考意图" : "日常咨询", refs.size(), examHits.size(),
                        System.currentTimeMillis() - searchStart);
                messages = lawAgent.buildMessages(session.getNickname(), history, refs, examHits,
                        lawProfile.chatHint(session.getUserId()));
            } else if (session.isDesignMode()) {
                // 锦囊前置：上一轮判 WISH 后已异步预取存档，本轮直接读档（正常零等待）
                String counsel = latestCounsel(session.getId());
                log.info("锦囊读档（sessionId={}，{}）", session.getId(),
                        counsel != null ? "命中存档，零等待注入" : "无存档，现场推演兜底");
                if (counsel == null) {
                    // 无存档才现场推演兜底（双星约 5~10 秒，先告知前端"掐指一算"）
                    sink.next(new StreamEvent("thinking", "仙君掐指一算，请星君推演锦囊…"));
                    try {
                        counsel = counselService.consult(session.getId(), buildCounselContext(history, null));
                    } catch (Exception e) {
                        log.warn("仙班推演异常（仙官自行作答）：{}", e.getMessage());
                    }
                } else {
                    sink.next(new StreamEvent("thinking", "翻出仙班锦囊，铺开营造图…"));
                }
                if (counsel != null) {
                    // 营造图随本轮下发：前端面板展示共创进展（旧版纯文本存档前端解析失败自动跳过）
                    sink.next(new StreamEvent("design", counsel));
                }
                messages = buildHostMessages(session, history, counsel);
            } else {
                messages = buildHostMessages(session, history);
            }
            sink.next(new StreamEvent("thinking", lawMode ? "法学助手凝神酝酿答话…" : "仙君凝神酝酿答话…"));
            final long llmStart = System.currentTimeMillis();
            final boolean[] firstToken = {true};
            model.chat(messages, new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String token) {
                    if (firstToken[0]) {
                        firstToken[0] = false;
                        log.info("LLM 首 token 延迟 {}ms（sessionId={}，{}通道）",
                                System.currentTimeMillis() - llmStart, session.getId(),
                                lawMode ? "法学" : "仙官");
                    }
                    sink.next(new StreamEvent("chunk", token));
                }

                @Override
                public void onCompleteResponse(ChatResponse completeResponse) {
                    String reply = completeResponse.aiMessage().text();
                    if (lawMode) {
                        // 法学模式收口：不炼卡、不碰设计模式，只持久化消息（引用 RAG 后续接入）
                        try {
                            if (greet) {
                                // 出场触发无用户消息，saveRound 会重复存旧消息，只存开场白
                                saveGreeting(session.getId(), reply);
                            } else {
                                saveRound(session.getId(), history, reply, null);
                            }
                        } catch (Exception e) {
                            log.warn("消息入库失败（不影响对话）：{}", e.getMessage());
                        }
                        log.info("法学模式收口（sessionId={}，回复 {} 字，流式生成总耗时 {}ms）",
                                session.getId(), reply == null ? 0 : reply.length(),
                                System.currentTimeMillis() - llmStart);
                        sink.next(new StreamEvent("done", null));
                        sink.complete();
                        return;
                    }
                    // 回答结束后跑一次状态提炼（WISH/NO_CARD/炼卡），失败不影响对话结束
                    ExtractResult result = null;
                    try {
                        result = extract(session, history, reply);
                    } catch (Exception e) {
                        log.warn("功能卡提炼失败（不影响对话）：{}", e.getMessage());
                    }
                    final Long cardId = result == null ? null : result.cardId();
                    if (cardId != null) {
                        cardRepository.findById(cardId)
                                .ifPresent(c -> sink.next(new StreamEvent("card", toJson(c))));
                    }
                    // 设计模式流转：愿望信号开班（下轮带锦囊），炼卡或转闲聊散班
                    try {
                        session.setDesignMode(result != null && result.designMode());
                        sessionRepository.save(session);
                    } catch (Exception e) {
                        log.warn("设计模式保存失败（不影响对话）：{}", e.getMessage());
                    }
                    // 本轮消息入库（user → assistant → 卡片占位），历史恢复以此为准
                    try {
                        saveRound(session.getId(), history, reply, cardId);
                    } catch (Exception e) {
                        log.warn("消息入库失败（不影响对话）：{}", e.getMessage());
                    }
                    // 锦囊前置：愿望还在打磨就为下一轮异步备好锦囊（双星耗时移到场外，下轮零等待）
                    if (result != null && result.designMode()) {
                        prefetchCounsel(session.getId(), buildCounselContext(history, reply));
                    }
                    log.info("本轮收口（sessionId={}，回复 {} 字，{}）", session.getId(),
                            reply == null ? 0 : reply.length(),
                            result == null ? "提炼失败，维持原状态"
                                    : result.cardId() != null ? "炼卡#" + result.cardId()
                                    : result.designMode() ? "WISH 续聊" : "NO_CARD 散班");
                    sink.next(new StreamEvent("done", null));
                    sink.complete();
                }

                @Override
                public void onError(Throwable error) {
                    log.warn("主理人模型调用失败", error);
                    sink.next(new StreamEvent("error", "仙官暂时开小差了，稍后再试试～"));
                    sink.complete();
                }
            });
        })
                // 关键：订阅切到后台线程。Flux.create 的 lambda 含阻塞式 LLM 调用，
                // 若在 Servlet 线程同步跑完，ResponseBodyEmitter 未 initialize，
                // 所有 send 只会攒进 earlySendAttempts，等 initialize 后一次性 replay（表现为整段回答瞬间涌出）。
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 设计模式下给双星的讨论背景（最近几轮对话 + 本轮仙官回复；设计稿回灌由 IdeaCounselService 自行截取处理） */
    private String buildCounselContext(List<InMessage> history, String hostReply) {
        StringBuilder sb = new StringBuilder();
        List<InMessage> window = history.size() > EXTRACT_WINDOW
                ? history.subList(history.size() - EXTRACT_WINDOW, history.size())
                : history;
        for (InMessage m : window) {
            if ("user".equals(m.role())) {
                sb.append("客人：").append(m.content()).append('\n');
            } else if ("assistant".equals(m.role())) {
                sb.append("仙官：").append(m.content()).append('\n');
            }
        }
        if (hostReply != null && !hostReply.isBlank()) {
            sb.append("仙官：").append(hostReply).append('\n');
        }
        return sb.toString().trim();
    }

    /** 最近一份锦囊存档原文（JSON 信封；注入/提炼经 renderForHost 渲染）；无存档返回 null */
    private String latestCounsel(Long sessionId) {
        List<IdeaMessageEntity> list = messageRepository.findBySessionIdAndRoleOrderByIdAsc(sessionId, "counsel");
        return list.isEmpty() ? null : list.get(list.size() - 1).getContent();
    }

    /** 异步预取下一轮锦囊：失败只记日志，下一轮读档为空时现场兜底 */
    private void prefetchCounsel(Long sessionId, String context) {
        CompletableFuture.runAsync(() -> {
            try {
                log.info("锦囊预取开始（sessionId={}）", sessionId);
                counselService.consult(sessionId, context);
            } catch (Exception e) {
                log.warn("锦囊预取失败（下一轮现场兜底）：{}", e.getMessage());
            }
        });
    }

    /** 提炼结果：designMode 为下一轮是否带锦囊；cardId 非空表示本轮炼出了卡 */
    private record ExtractResult(boolean designMode, Long cardId) {}

    /** 提炼本轮对话状态（WISH/NO_CARD/炼卡）并在炼卡时入库，返回设计模式流转与卡片 id */
    private ExtractResult extract(IdeaSessionEntity session, List<InMessage> history, String hostReply) {
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            return new ExtractResult(false, null);
        }
        List<IdeaCardEntity> existingCards = cardRepository.findAllByOrderByCreativityDescCreatedAtDesc()
                .stream().limit(SHELF_MAX).toList();
        StringBuilder titles = new StringBuilder(existingCards.isEmpty() ? "（还没有）" : "");
        for (IdeaCardEntity card : existingCards) {
            titles.append("《").append(card.getTitle()).append("》：")
                    .append(card.getSummary() == null ? "" : card.getSummary()).append('\n');
        }
        // 锦囊只取最新一条：老锦囊会过时，全量拼接还会撑爆 prompt
        String counsel = latestCounsel(session.getId());
        String counsels = counsel == null ? "（本场还没有锦囊）" : counselService.renderForHost(counsel);

        StringBuilder dialog = new StringBuilder();
        List<InMessage> window = history.size() > EXTRACT_WINDOW
                ? history.subList(history.size() - EXTRACT_WINDOW, history.size())
                : history;
        for (InMessage m : window) {
            if ("user".equals(m.role())) {
                dialog.append("客人：").append(m.content()).append("\n");
            } else if ("assistant".equals(m.role())) {
                dialog.append("仙官：").append(m.content()).append("\n");
            }
        }
        dialog.append("仙官：").append(hostReply).append("\n");

        // 宣布检测（代码级）：仙官固定话术同时含"功能卡"与"整理/炼成"即视为已宣布成卡，不依赖模型语义判断
        boolean announced = hostReply != null && hostReply.contains("功能卡")
                && (hostReply.contains("整理") || hostReply.contains("炼成"));
        log.info("宣布检测（sessionId={}，announced={}）", session.getId(), announced);
        String prompt = EXTRACT_PROMPT
                .replace("{titles}", titles.toString().trim())
                .replace("{counsels}", counsels.trim())
                .replace("{announced}", announced
                        ? "是——仙官本轮已宣布成卡，条件 b 满足"
                        : "否——仙官本轮未宣布成卡，条件 b 不满足")
                .replace("{dialog}", dialog.toString());
        // 提炼器退避重试：失败多为账户限流（1302），等 2 秒错峰再试一次，两败才抛给外层兜底
        String reply = null;
        RuntimeException lastError = null;
        com.mengx.server.llm.LlmUsage.set("extractor");
        try {
            for (int attempt = 0; attempt < 2 && reply == null; attempt++) {
                if (attempt > 0) {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                try {
                    reply = chatModel
                            .chat(List.of(SystemMessage.from(EXTRACT_SYSTEM), UserMessage.from(prompt)))
                            .aiMessage()
                            .text();
                } catch (RuntimeException e) {
                    lastError = e;
                    log.warn("提炼器模型调用失败（第 {} 次）：{}", attempt + 1, e.getMessage());
                }
            }
        } finally {
            com.mengx.server.llm.LlmUsage.clear();
        }
        if (reply == null) {
            throw lastError != null ? lastError : new IllegalStateException("提炼器无输出");
        }
        return parseExtract(reply, session, existingCards);
    }

    /** 解析提炼结果：WISH 开班待续聊，JSON 炼卡入库（含方案卷轴），NO_CARD/异常散班回陪聊 */
    private ExtractResult parseExtract(String reply, IdeaSessionEntity session, List<IdeaCardEntity> existingCards) {
        if (reply == null) {
            return new ExtractResult(false, null);
        }
        String text = reply.trim();
        // 剥 markdown 围栏（```json … ```）：模型偶尔裹输出，不剥会误判为无法识别
        if (text.startsWith("```")) {
            int firstBreak = text.indexOf('\n');
            int closing = text.lastIndexOf("```");
            if (firstBreak >= 0) {
                text = (closing > firstBreak
                        ? text.substring(firstBreak + 1, closing)
                        : text.substring(firstBreak + 1)).trim();
            }
        }
        if (text.toUpperCase().startsWith("WISH")) {
            log.info("提炼器判定 WISH（继续打磨）");
            return new ExtractResult(true, null);
        }
        if (text.toUpperCase().startsWith("NO_CARD")) {
            log.info("提炼器判定 NO_CARD（散班回陪聊）");
            return new ExtractResult(false, null);
        }
        // 尝试解析功能卡 JSON（截取首个 {…}）
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            log.warn("提炼器输出无法识别（视为 NO_CARD）：{}", truncate(text, 80));
            return new ExtractResult(false, null);
        }
        try {
            JsonNode node = objectMapper.readTree(text.substring(start, end + 1));
            String title = truncate(node.path("title").asText("").trim(), 100);
            if (title.isEmpty() || isDuplicateTitle(title, existingCards)) {
                return new ExtractResult(false, null);
            }
            if (cardRepository.countBySessionId(session.getId()) >= MAX_CARDS_PER_SESSION) {
                log.info("提炼器判 JSON 但本场卡数已到上限（{}），不再炼卡", MAX_CARDS_PER_SESSION);
                return new ExtractResult(false, null);
            }
            String summary = node.path("summary").asText("").trim();
            IdeaCardEntity card = new IdeaCardEntity(
                    session.getId(),
                    session.getNickname(),
                    title,
                    summary.isEmpty() ? "待聊" : truncate(summary, 500),
                    field(node, "targetUser"),
                    field(node, "triggerScene"),
                    field(node, "reaction"),
                    field(node, "fallback"),
                    clamp(node.path("creativity").asInt(3)));
            // 双层卡：方案卷轴（仙班共创沉淀）+ 优先级随主卡一并入库
            JsonNode design = node.path("design");
            String designJson = design.isObject() && design.size() > 0
                    ? objectMapper.writeValueAsString(design) : null;
            card.applyDesign(
                    truncate(node.path("scale").asText("功能"), 10),
                    truncate(node.path("priority").asText("中"), 10),
                    designJson);
            IdeaCardEntity saved = cardRepository.save(card);
            log.info("提炼器炼卡《{}》（体量={}）", saved.getTitle(), saved.getScale());
            return new ExtractResult(false, saved.getId());
        } catch (Exception e) {
            log.warn("功能卡 JSON 解析失败：{}", e.getMessage());
            return new ExtractResult(false, null);
        }
    }

    /** 路由 L2 细判的对话背景：最近几轮（客人/回答 逐行），不含本轮消息 */
    private String buildRouteContext(List<InMessage> history) {
        StringBuilder sb = new StringBuilder();
        int from = Math.max(0, history.size() - 1 - ROUTE_CONTEXT_WINDOW);
        for (int i = from; i < history.size() - 1; i++) {
            InMessage m = history.get(i);
            if ("user".equals(m.role())) {
                sb.append("客人：");
            } else if ("assistant".equals(m.role())) {
                sb.append("回答：");
            } else {
                continue;
            }
            sb.append(m.content() == null ? "" : m.content()).append('\n');
        }
        return sb.toString().trim();
    }

    /** 意图路由用：取 history 中最后一条 user 消息文本（无则空串） */
    private String latestUserText(List<InMessage> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            if ("user".equals(history.get(i).role())) {
                return history.get(i).content();
            }
        }
        return "";
    }

    // ==================== 法学刷题流已拆至 LawQuizFlowService（idea 包，域内子意图编排） ====================

    /** 编号路指代解析：「那第20条呢」没写法名时，取最近对话中出现过的法名作检索提示 */
    private String lawCodeHint(List<InMessage> history) {
        int from = Math.max(0, history.size() - 8);
        for (int i = history.size() - 1; i >= from; i--) {
            String code = lawSearch.detectLawCode(history.get(i).content());
            if (code != null) {
                return code;
            }
        }
        return null;
    }

    /** 法学助手出场：只存开场白（触发无用户消息，saveRound 会重复存旧消息） */
    private void saveGreeting(Long sessionId, String reply) {
        if (reply == null || reply.isBlank()) {
            return;
        }
        messageRepository.save(new IdeaMessageEntity(sessionId, "assistant", reply));
    }

    private void saveRound(Long sessionId, List<InMessage> history, String assistantReply, Long cardId) {
        InMessage lastUser = null;
        for (int i = history.size() - 1; i >= 0; i--) {
            if ("user".equals(history.get(i).role())) {
                lastUser = history.get(i);
                break;
            }
        }
        if (lastUser == null) {
            return;
        }
        List<IdeaMessageEntity> round = new ArrayList<>(3);
        round.add(new IdeaMessageEntity(sessionId, "user", lastUser.content()));
        if (assistantReply != null && !assistantReply.isBlank()) {
            round.add(new IdeaMessageEntity(sessionId, "assistant", assistantReply));
        }
        if (cardId != null) {
            round.add(new IdeaMessageEntity(sessionId, "card", String.valueOf(cardId)));
        }
        messageRepository.saveAll(round);
    }

    /** 代码级兜底：新标题与已有卡标题归一化后互含、或 bigram 相似度≥0.7，判为重复卡丢弃（防 LLM 语义判断失手，许愿池复用） */
    static boolean isDuplicateTitle(String newTitle, List<IdeaCardEntity> existingCards) {
        String a = normalizeTitle(newTitle);
        if (a.isEmpty()) {
            return true;
        }
        for (IdeaCardEntity card : existingCards) {
            String b = normalizeTitle(card.getTitle());
            if (b.isEmpty()) {
                continue;
            }
            if (a.contains(b) || b.contains(a) || bigramSimilarity(a, b) >= 0.7) {
                log.info("拦截重复炼卡：新《{}》与已有《{}》相似", newTitle, card.getTitle());
                return true;
            }
        }
        return false;
    }

    /** 去掉空白与标点，只留汉字/字母/数字 */
    private static String normalizeTitle(String s) {
        return s == null ? "" : s.replaceAll("[\\p{P}\\p{Z}\\p{S}]", "");
    }

    /** 字符 bigram 的 Jaccard 相似度（中文无分词的轻量相似判断） */
    private static double bigramSimilarity(String a, String b) {
        if (a.length() < 2 || b.length() < 2) {
            return 0;
        }
        Set<String> ga = bigrams(a);
        Set<String> gb = bigrams(b);
        Set<String> inter = new HashSet<>(ga);
        inter.retainAll(gb);
        Set<String> union = new HashSet<>(ga);
        union.addAll(gb);
        return union.isEmpty() ? 0 : (double) inter.size() / union.size();
    }

    private static Set<String> bigrams(String s) {
        Set<String> set = new HashSet<>();
        for (int i = 0; i < s.length() - 1; i++) {
            set.add(s.substring(i, i + 2));
        }
        return set;
    }

    private static String field(JsonNode node, String name) {
        String v = node.path(name).asText("").trim();
        return v.isEmpty() ? "待聊" : truncate(v, 200);
    }

    private static int clamp(int value) {
        return Math.max(1, Math.min(5, value));
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private String toJson(IdeaCardEntity card) {
        try {
            return objectMapper.writeValueAsString(IdeaCardDto.from(card));
        } catch (Exception e) {
            return "{}";
        }
    }

    /** 通用 JSON 序列化（citations 等非卡片事件） */
    private String toJsonValue(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "[]";
        }
    }
}
