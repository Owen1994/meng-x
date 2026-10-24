package com.mengx.server.idea.agent;

/**
 * 访谈计划（存档信封 product 段，历史命名保留）：
 * 本轮澄清哪个维度、缺什么、建议怎么问。
 * proposal/confirmed 为共创单官新增：建议方案（供主持人转述点头）与纯确认标记；
 * 存量旧档反序列化时为 null，读取方需判空。
 */
public record InterviewPlan(String agenda, String missed, String question,
                            String risk, String priority, String decompose,
                            String proposal, Boolean confirmed) {
}
