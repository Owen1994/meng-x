package com.mengx.server.forge;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/**
 * 黑板（方案 §6.1）：施工包信封 JSON（forge_jobs.stage_payload 的内存形态）。
 * 分段唯一写者——analyst 写 product/openQuestions，advisor 写 domainDesign，
 * architect 写 engineering，reviewer 写 review；岗之间禁止直接对话，只经黑板接力。
 * 信封一律带 version（§8.2）；解析用独立 Mapper 且容忍未知字段（项目惯例，向后兼容加段）。
 */
public class ForgeBlackboard {

    public static final int VERSION = 1;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private int version = VERSION;
    private Long cardId;
    private Integer designVersion;

    /** 产品深化段（analyst 唯一写者） */
    private ProductSegment product;

    /** 领域设计段（advisor.*，Step 6+ 落位；Object 容纳各领域自有结构） */
    private Object domainDesign;

    /** 工程施工包段（architect，Step 7+ 落位） */
    private Object engineering;

    /** 审校段（reviewer，Step 7+ 落位） */
    private Object review;

    /** 待决问题清单（产品口径，缺省项/快照待聊格的补写记录，供终审逐条把关；与挂起问人的问题两回事） */
    private List<String> openQuestions = new ArrayList<>();

    public ForgeBlackboard() {}

    public ForgeBlackboard(Long cardId, Integer designVersion) {
        this.cardId = cardId;
        this.designVersion = designVersion;
    }

    /** 从工单信封加载：空信封新建；损坏显式上抛（工部失败显式上抛，不静默降级——方案 §13） */
    public static ForgeBlackboard load(ForgeJobEntity job) {
        String payload = job.getStagePayload();
        if (payload == null || payload.isBlank()) {
            return new ForgeBlackboard(job.getCardId(), job.getDesignVersion());
        }
        ForgeBlackboard board;
        try {
            board = MAPPER.readValue(payload, ForgeBlackboard.class);
        } catch (Exception e) {
            throw new IllegalStateException("黑板信封解析失败（jobId=" + job.getId() + "）", e);
        }
        if (board.cardId == null) {
            board.cardId = job.getCardId();
        }
        if (board.designVersion == null) {
            board.designVersion = job.getDesignVersion();
        }
        return board;
    }

    /** 序列化回信封 JSON（先落盘后交棒，§7.5；失败上抛由编排器兜底标 FAILED） */
    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (Exception e) {
            throw new IllegalStateException("黑板信封序列化失败", e);
        }
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public Long getCardId() {
        return cardId;
    }

    public void setCardId(Long cardId) {
        this.cardId = cardId;
    }

    public Integer getDesignVersion() {
        return designVersion;
    }

    public void setDesignVersion(Integer designVersion) {
        this.designVersion = designVersion;
    }

    public ProductSegment getProduct() {
        return product;
    }

    public void setProduct(ProductSegment product) {
        this.product = product;
    }

    public Object getDomainDesign() {
        return domainDesign;
    }

    public void setDomainDesign(Object domainDesign) {
        this.domainDesign = domainDesign;
    }

    public Object getEngineering() {
        return engineering;
    }

    public void setEngineering(Object engineering) {
        this.engineering = engineering;
    }

    public Object getReview() {
        return review;
    }

    public void setReview(Object review) {
        this.review = review;
    }

    public List<String> getOpenQuestions() {
        return openQuestions == null ? new ArrayList<>() : openQuestions;
    }

    public void setOpenQuestions(List<String> openQuestions) {
        this.openQuestions = openQuestions == null ? new ArrayList<>() : openQuestions;
    }
}
