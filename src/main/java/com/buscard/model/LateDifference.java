package com.buscard.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 晚到消费差额记录。
 * 挂失之后才同步/清算、但实际发生在挂失之前的车费：原始 FARE 流水保留，
 * 同时在此独立登记一笔差额，冲减可转余额，绝不修改旧交易抹平余额。
 *
 * 若晚到发生在余额转移之前，转移时自动计入；若发生在转移之后（钱已转走），
 * 记为未解决差额，由客服在下一张卡（卡链）上追账，全程留痕。
 */
@Entity
@Table(name = "late_differences")
public class LateDifference {

    @Id
    private String id;

    @Column(nullable = false)
    private String replacementId;

    @Column(nullable = false)
    private String cardId;

    /** 触发该差额的原始 FARE 交易号 */
    @Column(nullable = false)
    private String txnId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false)
    private Instant occurredAt;

    @Column(nullable = false)
    private Instant recordedAt;

    /** BEFORE_TRANSFER（转移前已冲减） / AFTER_TRANSFER（转移后追账中） / RECOVERED（已追账） */
    @Column(nullable = false)
    private String stage;

    @Column(nullable = false)
    private boolean resolved = false;

    private String resolution;
    private Instant resolvedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getReplacementId() { return replacementId; }
    public void setReplacementId(String replacementId) { this.replacementId = replacementId; }
    public String getCardId() { return cardId; }
    public void setCardId(String cardId) { this.cardId = cardId; }
    public String getTxnId() { return txnId; }
    public void setTxnId(String txnId) { this.txnId = txnId; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }
    public Instant getRecordedAt() { return recordedAt; }
    public void setRecordedAt(Instant recordedAt) { this.recordedAt = recordedAt; }
    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }
    public boolean isResolved() { return resolved; }
    public void setResolved(boolean resolved) { this.resolved = resolved; }
    public String getResolution() { return resolution; }
    public void setResolution(String resolution) { this.resolution = resolution; }
    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }
}
