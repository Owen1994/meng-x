package com.mengx.server.routing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 通道注册表：集中登记各能力的路由元数据（默认通道 + 各专项能力）。
 * 分层路由的 L1 信号词与 L2 名册均从此生成；
 * 后续新能力（出题官、批改官、新道场…）在此加条目即可接入路由。
 */
@Component
public class ChannelRegistry {

    /** 默认通道：不设信号词——所有专项信号都不命中时消息落到这里（仙官本色陪聊 + 点子访谈） */
    public static final String DEFAULT_CHANNEL = "host";

    private final Map<String, ChannelSpec> channels = new LinkedHashMap<>();

    public ChannelRegistry() {
        register(new ChannelSpec(
                "law", "法学助手",
                "把消息当作法律问题来问——日常法律咨询（纠纷、维权、合同等）、法条或法律概念的含义、"
                        + "法律案例分析、法考备考、法律责任等，期待法律专业的解答",
                List.of(
                        "法条", "法学", "法考", "法律", "法院", "法规", "司法", "立法", "普法", "执法", "犯法", "学法",
                        "刑法", "民法", "宪法", "诉讼", "仲裁", "律师", "律所", "检察院", "公安", "拘留", "传唤", "自首",
                        "辩护", "公诉", "原告", "被告", "判决", "裁定", "量刑", "定罪", "罪", "犯罪", "违法", "侵权",
                        "缓刑", "拘役", "立案", "起诉", "上诉", "申诉", "证据", "案卷", "案件", "案例", "合同", "纠纷",
                        "官司", "维权", "权益", "备考", "判例", "条文", "条例", "治安", "离婚", "遗嘱", "继承", "债务",
                        "债权", "诈骗", "防卫", "真题", "试题", "学情", "学得怎么样", "正确率"),
                List.of(
                        "出题", "答题", "做题", "判分", "出几道", "几道题",
                        "考考我", "考我", "刷题", "练一练", "下一题", "继续出",
                        "主观题", "案例分析", "简答", "论述"),
                List.of(
                        new ChannelSpec.Example("刑法第13条说的情节显著轻微是什么意思？", "law"),
                        new ChannelSpec.Example("遗嘱都有哪几种形式？", "law"),
                        new ChannelSpec.Example("想看2024年真题", "law"),
                        new ChannelSpec.Example("给我出几道题练练", "law"),
                        new ChannelSpec.Example("租的房子房东不退押金该怎么办？", "law"),
                        new ChannelSpec.Example("我想做一个法律咨询助手，专门答合同问题", "host"))));
        register(new ChannelSpec(
                DEFAULT_CHANNEL, "仙官",
                "功能创意讨论（想给铺子设计新功能/新道场，即使点子内容本身涉及法律，比如想做一个法律咨询助手）、"
                        + "日常闲聊、以及其余一切话题",
                List.of(),
                List.of(),
                List.of(new ChannelSpec.Example("这个点子能落地吗，你觉得怎么样", "host"))));
    }

    private void register(ChannelSpec spec) {
        channels.put(spec.id(), spec);
    }

    public ChannelSpec get(String id) {
        return id == null ? null : channels.get(id);
    }

    /** 全部通道（强意图直达遍历用） */
    public java.util.Collection<ChannelSpec> all() {
        return channels.values();
    }

    /** 消息命中的专项通道集合（默认通道无信号词，不参与） */
    public List<ChannelSpec> signalMatches(String text) {
        List<ChannelSpec> hits = new ArrayList<>();
        if (text == null) {
            return hits;
        }
        for (ChannelSpec spec : channels.values()) {
            if (spec.id().equals(DEFAULT_CHANNEL) || spec.signals().isEmpty()) {
                continue;
            }
            for (String s : spec.signals()) {
                if (text.contains(s)) {
                    hits.add(spec);
                    break;
                }
            }
        }
        return hits;
    }

    /** L2 名册：各通道何时接手（含 few-shot 示例），拼进路由提示词 */
    public String buildRoster() {
        StringBuilder sb = new StringBuilder();
        for (ChannelSpec spec : channels.values()) {
            sb.append("- ").append(spec.name()).append("（channel=\"").append(spec.id()).append("\"）：")
                    .append(spec.routeDescription()).append('\n');
            for (ChannelSpec.Example ex : spec.examples()) {
                sb.append("  例：\"").append(ex.utterance()).append("\" → ").append(ex.channel()).append('\n');
            }
        }
        return sb.toString().trim();
    }
}
