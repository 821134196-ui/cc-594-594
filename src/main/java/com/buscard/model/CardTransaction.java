package com.buscard.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 卡片流水。任何余额变动都只能新增流水，历史流水永不修改、不删除。
 *
 * type:
 *  TOPUP        充值
 *  FARE         乘车扣款（闸机）
 *  TRANSFER_OUT 旧卡余额转出
 *  TRANSFER_IN  新卡余额转入
 *
 * status:
 *  POSTED   已入账（余额已变动）
 *  PENDING  在途消费，已在闸机发生、尚未清算入账
 *
 * 晚到：挂失之后才同步入账的 FARE，late=true，原始流水保留。
 */
@Entity
@Table(name = "card_transactions")
public class CardTransaction {

    @Id
    private String id;

    @Column(nullable = false)
    private String cardId;

    @Column(nullable = false)
    private String type;

    /** 金额一律为正数，方向由 type 表达 */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    /** 入账后卡内余额（在途清算入账前，旧卡挂失瞬间的快照不受影响） */
    @Column(precision = 12, scale = 2)
    private BigDecimal balanceAfter;

    @Column(nullable = false)
    private String status;

    /** 业务发生时间（闸机本地时间） */
    @Column(nullable = false)
    private Instant occurredAt;

    /** 系统入账时间（PENDING 在途流水尚未入账时为空） */
    private Instant postedAt;

    /** 清算回执号（在途消费），全局唯一，用于幂等 */
    @Column(unique = true)
    private String receiptId;

    /** 离线设备自增引用，全局唯一，用于幂等 */
    @Column(unique = true)
    private String pendingRef;

    @Column(nullable = false)
    private boolean late = false;

    private String note;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getCardId() { return cardId; }
    public void setCardId(String cardId) { this.cardId = cardId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public BigDecimal getBalanceAfter() { return balanceAfter; }
    public void setBalanceAfter(BigDecimal balanceAfter) { this.balanceAfter = balanceAfter; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }
    public Instant getPostedAt() { return postedAt; }
    public void setPostedAt(Instant postedAt) { this.postedAt = postedAt; }
    public String getReceiptId() { return receiptId; }
    public void setReceiptId(String receiptId) { this.receiptId = receiptId; }
    public String getPendingRef() { return pendingRef; }
    public void setPendingRef(String pendingRef) { this.pendingRef = pendingRef; }
    public boolean isLate() { return late; }
    public void setLate(boolean late) { this.late = late; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
