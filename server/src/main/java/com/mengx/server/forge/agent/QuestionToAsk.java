package com.mengx.server.forge.agent;

import java.util.List;

/**
 * 工部岗位模型输出的「挂问客人」结构（需求分析师 / 领域顾问共用）：
 * type=问题归类（产品/口味/合规），question=原样转述的口语问法，
 * options=2~4 个候选（可空），defaultValue=建议默认项与理由（可空）。
 */
public record QuestionToAsk(
        String type,
        String question,
        List<String> options,
        String defaultValue) {
}
