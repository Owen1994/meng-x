package com.mengx.server.llm;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LlmUsageLogRepository extends JpaRepository<LlmUsageLogEntity, Long> {

    /** 成本查账：近 N 天按场景聚合 token 与调用次数（电表读数） */
    @Query(value = "SELECT scene, model, count(*) AS calls, sum(prompt_tokens) AS pt, "
            + "sum(completion_tokens) AS ct, round(avg(latency_ms)) AS avg_ms "
            + "FROM llm_usage_log WHERE created_at > now() - make_interval(days => :days) "
            + "GROUP BY scene, model ORDER BY pt DESC NULLS LAST", nativeQuery = true)
    List<Object[]> aggregateSince(@Param("days") int days);
}
