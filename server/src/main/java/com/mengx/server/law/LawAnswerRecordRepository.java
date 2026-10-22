package com.mengx.server.law;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LawAnswerRecordRepository extends JpaRepository<LawAnswerRecordEntity, Long> {

    List<LawAnswerRecordEntity> findByUserIdAndQuestionIdIn(Long userId, Collection<Long> questionIds);

    boolean existsByUserIdAndQuestionId(Long userId, Long questionId);

    /** 主观题重交：取该用户此题的最近一次批改记录 */
    LawAnswerRecordEntity findFirstByUserIdAndQuestionIdOrderByIdDesc(Long userId, Long questionId);

    /** 画像聚合：取用户全部答题记录（量级百级，内存聚合） */
    List<LawAnswerRecordEntity> findByUserId(Long userId);
}
