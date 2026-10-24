package com.mengx.server.idea;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface IdeaProposalRepository extends JpaRepository<IdeaProposalEntity, Long> {

    /** 归属校验：提案必须属于当前用户 */
    Optional<IdeaProposalEntity> findByIdAndUserId(Long id, Long userId);

    /** 某场次指定状态的提案（新→旧）；PENDING 理论上至多一条（单提议原则） */
    List<IdeaProposalEntity> findBySessionIdAndStatusOrderByIdDesc(Long sessionId, String status);

    /**
     * CAS：PENDING → ACCEPTED（许可制开班的原子闸门，返回受影响行数）。
     * 事务声明在仓库方法上：调用方可能运行在自定义线程池（observerPool）等无事务上下文，
     * 缺失时 @Modifying 会抛 TransactionRequiredException: Executing an update/delete query。
     */
    @Transactional
    @Modifying
    @Query("update IdeaProposalEntity p set p.status = 'ACCEPTED', p.resolvedAt = :now "
            + "where p.id = :id and p.status = 'PENDING'")
    int casAccept(@Param("id") Long id, @Param("now") Instant now);

    /** CAS：PENDING → DISMISSED（用户主动拒绝，返回受影响行数） */
    @Transactional
    @Modifying
    @Query("update IdeaProposalEntity p set p.status = 'DISMISSED', p.resolvedAt = :now "
            + "where p.id = :id and p.status = 'PENDING'")
    int casDismiss(@Param("id") Long id, @Param("now") Instant now);

    /** 单提议原则：同场既有 PENDING 提案全部置 SUPERSEDED（排除刚落库的新提案） */
    @Transactional
    @Modifying
    @Query("update IdeaProposalEntity p set p.status = 'SUPERSEDED', p.resolvedAt = :now "
            + "where p.sessionId = :sessionId and p.status = 'PENDING' and p.id <> :excludeId")
    int supersedePending(@Param("sessionId") Long sessionId, @Param("excludeId") Long excludeId,
                         @Param("now") Instant now);
}
