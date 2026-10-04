package com.transit.entity;

import com.transit.domain.TransferStatus;
import com.transit.entity.BaseEntity;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * 余额转移尝试记录（追加式，绝不更新覆盖）。
 * 成功时 outTxnId / inTxnId 配对，两笔金额恒等；失败也落一行并记录原因，
 * 未完成的转移不会丢，客服可据此重试/追查。
 */
@Entity
@Table(name = "balance_transfer", indexes = {
        @Index(name = "idx_transfer_case", columnList = "case_id")
})
public class BalanceTransferEntity extends BaseEntity {

    @Column(nullable = false)
    public Long caseId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public TransferStatus status;

    /** 尝试序号（同一案件从 1 递增） */
    public int attemptNo;

    @Column(nullable = false)
    public long amountCents;

    public Long outTxnId;
    public Long inTxnId;

    public Instant attemptedAt;

    @Column(length = 256)
    public String note;
}
