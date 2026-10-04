package com.transit.entity;

import com.transit.domain.CaseStatus;
import com.transit.entity.BaseEntity;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * 补卡案件。
 * 一张旧卡只能关联一张有效新卡：oldCardId 上的部分唯一索引（由应用 + 唯一约束保证），
 * 重复补卡会被拒绝并在 CaseEvent 留痕。
 * 案件 FAILED / 新卡再次挂失都不会删除案件，未完成的转移继续可追踪。
 */
@Entity
@Table(name = "replacement_case", indexes = {
        @Index(name = "idx_case_oldcard", columnList = "old_card_id"),
        @Index(name = "idx_case_newcard", columnList = "new_card_id"),
        @Index(name = "idx_case_status", columnList = "status")
})
public class ReplacementCaseEntity extends BaseEntity {

    @Column(nullable = false, unique = true, length = 32)
    public String caseNo;

    @Column(nullable = false)
    public Long oldCardId;

    public Long newCardId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    public CaseStatus status;

    /** 受理客服 */
    @Column(length = 32)
    public String agentId;

    /** 挂失受理时旧卡“已清算余额”快照，转移对账基准 */
    public long oldCardBalanceAtReportCents;

    /** 截至挂失时点已确认的在途消费合计（可能后续还有晚到交易） */
    public long pendingFaresAtReportCents;

    /** 已从旧卡转出总额（分），失败重试不累加 */
    public long transferredOutCents = 0;

    /** 已转入新卡总额（分），必须与 transferredOutCents 恒等 */
    public long transferredInCents = 0;

    /** 晚到消费追扣累计（分） */
    public long lateClawbackCents = 0;

    public Instant createdAt;
    public Instant blockedAt;
    public Instant newCardIssuedAt;
    public Instant transferredAt;
    public Instant closedAt;

    @Column(length = 256)
    public String failureReason;

    public static ReplacementCaseEntity activeByOldCard(long oldCardId) {
        return find("oldCardId = ?1 and status <> ?2", oldCardId, CaseStatus.FAILED)
                .firstResult();
    }
}
