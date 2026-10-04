package com.buscard.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 闸机离线刷卡记录（闸机本地队列的模拟）。
 * 离线期间闸机只记录、不扣中心账户余额；同步后变为待清算在途消费。
 */
@Entity
@Table(name = "offline_swipe_records")
public class OfflineSwipeRecord {

    /** 设备侧生成的全局唯一引用，同步时幂等去重 */
    @Id
    private String pendingRef;

    @Column(nullable = false)
    private String cardId;

    @Column(nullable = false)
    private String gateId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false)
    private Instant occurredAt;

    /** 是否已同步到中心成为 PENDING 流水 */
    @Column(nullable = false)
    private boolean synced = false;
    private Instant syncedAt;

    /** 是否已清算入账 */
    @Column(nullable = false)
    private boolean cleared = false;
    private Instant clearedAt;

    /** 清算回执号 */
    private String receiptId;

    public String getPendingRef() { return pendingRef; }
    public void setPendingRef(String pendingRef) { this.pendingRef = pendingRef; }
    public String getCardId() { return cardId; }
    public void setCardId(String cardId) { this.cardId = cardId; }
    public String getGateId() { return gateId; }
    public void setGateId(String gateId) { this.gateId = gateId; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }
    public boolean isSynced() { return synced; }
    public void setSynced(boolean synced) { this.synced = synced; }
    public Instant getSyncedAt() { return syncedAt; }
    public void setSyncedAt(Instant syncedAt) { this.syncedAt = syncedAt; }
    public boolean isCleared() { return cleared; }
    public void setCleared(boolean cleared) { this.cleared = cleared; }
    public Instant getClearedAt() { return clearedAt; }
    public void setClearedAt(Instant clearedAt) { this.clearedAt = clearedAt; }
    public String getReceiptId() { return receiptId; }
    public void setReceiptId(String receiptId) { this.receiptId = receiptId; }
}
