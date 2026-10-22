package com.mengx.server.law;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LawQuestionRepository extends JpaRepository<LawQuestionEntity, Long> {

    List<LawQuestionEntity> findByContentHashIn(Collection<String> hashes);

    long countBySource(String source);

    /** 向量近邻召回（仅真题 origin；生成题已在出题流程复用，不进 RAG 检索） */
    @Query(value = "SELECT * FROM law_questions WHERE embedding IS NOT NULL AND source = 'origin' "
            + "ORDER BY embedding <=> CAST(:q AS vector) LIMIT :k", nativeQuery = true)
    List<LawQuestionEntity> findNearest(@Param("q") String q, @Param("k") int k);

    /** 年份感知：按年份取真题（「想看2024年真题」类元查询，向量路对此无效，直接查库） */
    @Query(value = "SELECT * FROM law_questions WHERE source = 'origin' AND year = :year "
            + "ORDER BY id LIMIT :k", nativeQuery = true)
    List<LawQuestionEntity> findByYearOrigin(@Param("year") int year, @Param("k") int k);
}
