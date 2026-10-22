package com.mengx.server.law;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LawArticleRepository extends JpaRepository<LawArticleEntity, Long> {

    /** 修订 upsert 锚点：(law_code, article_no) 定位既有行 */
    Optional<LawArticleEntity> findByLawCodeAndArticleNo(String lawCode, String articleNo);

    /** 编号检索：「民法典第311条」→ law_code + 数值条号精确命中 */
    Optional<LawArticleEntity> findByLawCodeAndArticleNoInt(String lawCode, Integer articleNoInt);

    List<LawArticleEntity> findByLawCodeOrderByArticleNoIntAsc(String lawCode);

    /** 幂等判断：一次导入任务把库里既有 hash 拉出来比对，跳过未变化条目 */
    List<LawArticleEntity> findByContentHashIn(Collection<String> hashes);

    /** 库内全部法律编码（法名匹配用，distinct 轻） */
    @Query("SELECT DISTINCT a.lawCode FROM LawArticleEntity a")
    List<String> findAllLawCodes();

    long countByLawType(String lawType);

    /** 向量近邻（余弦距离）：q 为向量字面量 '[v1,v2,…]'，全量小表无需 ivfflat 索引 */
    @Query(value = "SELECT * FROM law_articles WHERE embedding IS NOT NULL "
            + "ORDER BY embedding <=> CAST(:q AS vector) LIMIT :k", nativeQuery = true)
    List<LawArticleEntity> findNearest(@Param("q") String q, @Param("k") int k);

    /** trgm 词汇（pg_trgm word_similarity）：查询术语在条文中的覆盖率，捞字面命中
     *  （如「表见代理」直接命中合同编通则解释），与向量语义路互补；主力是向量路 */
    @Query(value = "SELECT * FROM law_articles WHERE word_similarity(:q, content) > 0.4 "
            + "ORDER BY word_similarity(:q, content) DESC LIMIT :k", nativeQuery = true)
    List<LawArticleEntity> findSimilarTrgm(@Param("q") String q, @Param("k") int k);
}
