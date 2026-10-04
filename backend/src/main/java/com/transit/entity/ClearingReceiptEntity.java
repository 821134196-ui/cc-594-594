package com.transit.entity;

import com.transit.domain.ReceiptStatus;
import com.transit.entity.BaseEntity;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * 清算回执登记。receiptNo 唯一：重复回执只标记 DUPLICATE 并返回首次结果，
 * 绝不重复清算任何流水、绝不重复转移一分钱。
 */
@Entity
@Table(name = "clearing_receipt", uniqueConstraints = {
        @UniqueConstraint(name = "uk_receipt_no", columnNames = "receipt_no")
})
public class ClearingReceiptEntity extends BaseEntity {

    @Column(nullable = false, length = 48)
    public String receiptNo;

    public Long caseId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public ReceiptStatus status;

    /** 本次回执中首次清算的行数（重复回执为 0） */
    public int settledLineCount;

    /** 本次首次清算金额合计绝对值（分），重复回执为 0 */
    public long settledAmountCents;

    public Instant receivedAt;
    public Instant firstReceivedAt;

    @Column(length = 256)
    public String note;
}
