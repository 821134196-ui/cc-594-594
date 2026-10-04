package com.transit.entity;

import com.transit.domain.AdjustmentStatus;
import com.transit.entity.BaseEntity;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * 晚到交易差额挂账。
 * 余额已转移后才清算到的旧卡消费：
 *  - 旧卡原 FARE 流水保留（PENDING->SETTLED），不修改金额；
 *  - 差额挂账，从新卡追扣（新卡余额不足/再次挂失时保持 PENDING_CLAWBACK，记录不丢）；
 *  - 每条差额与原流水一一对应，客服能追到“为什么可转余额少了/为什么新卡扣了这笔”。
 */
@Entity
@Table(name = "late_adjustment", indexes = {
        @Index(name = "idx_lateadj_case", columnList = "case_id"),
        @Index(name = "idx_lateadj_status", columnList = "status")
})
public class LateAdjustmentEntity extends BaseEntity {

    @Column(nullable = false)
    public Long caseId;

    @Column(nullable = false)
    public Long oldCardId;

    @Column(nullable = false)
    public Long newCardId;

    /** 晚到的原旧卡消费流水 id */
    @Column(nullable = false)
    public Long originalFareTxnId;

    /** 旧卡侧 LATE_OFFSET 挂平分录 id */
    public Long offsetTxnId;

    /** 新卡侧追扣流水 id（成功后填） */
    public Long clawbackTxnId;

    @Column(nullable = false)
    public long amountCents;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    public AdjustmentStatus status;

    public Instant discoveredAt;
    public Instant resolvedAt;

    @Column(length = 256)
    public String note;
}
