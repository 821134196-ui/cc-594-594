package com.buscard.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 补卡单（一次挂失补卡业务的聚合根）。
 *
 * 状态机：
 *  REPORTED     已挂失停用，等待在途清算（newCardId 可能还没有）
 *  ISSUED       新卡已制出，等待清算完成后转移
 *  TRANSFERRED  余额已全部转入新卡（旧卡终态 REPLACED）
 *  FAILED       制卡失败等可重试故障，单据与未完成转移保留，不删除
 *  RE_REPORTED  新卡再次挂失，本单关闭，另开新单沿卡链继续
 *
 * 一张旧卡只对应一条补卡单（oldCardId 唯一），从根上防止重复补卡。
 */
@Entity
@Table(name = "replacements")
public class Replacement {

    @Id
    private String id;

    @Column(nullable = false, unique = true)
    private String oldCardId;

    private String newCardId;

    @Column(nullable = false)
    private String holderName;

    @Column(nullable = false)
    private String status;

    /** 挂失瞬间旧卡余额快照，可转余额计算的起点 */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal reportedBalance;

    /** 挂失前已发生、随后清算的在途消费合计 */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal pendingSettledTotal = BigDecimal.ZERO;

    /** 挂失后晚到消费合计（发生时间在挂失前、入账在挂失后） */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal lateTotal = BigDecimal.ZERO;

    /** 当前可转余额 = reportedBalance - pendingSettledTotal - lateTotal */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal transferableAmount;

    /** 已实际转入新卡金额（转移是一次性的，必须等于当时的可转余额） */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal transferredAmount = BigDecimal.ZERO;

    /** 转移清算回执号，唯一：重复回执不得多转钱 */
    @Column(unique = true)
    private String transferReceiptId;

    private int attemptCount;

    private String failReason;

    @Column(nullable = false)
    private Instant reportedAt;
    private Instant issuedAt;
    private Instant transferredAt;
    private Instant closedAt;

    @Version
    private long version;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getOldCardId() { return oldCardId; }
    public void setOldCardId(String oldCardId) { this.oldCardId = oldCardId; }
    public String getNewCardId() { return newCardId; }
    public void setNewCardId(String newCardId) { this.newCardId = newCardId; }
    public String getHolderName() { return holderName; }
    public void setHolderName(String holderName) { this.holderName = holderName; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public BigDecimal getReportedBalance() { return reportedBalance; }
    public void setReportedBalance(BigDecimal reportedBalance) { this.reportedBalance = reportedBalance; }
    public BigDecimal getPendingSettledTotal() { return pendingSettledTotal; }
    public void setPendingSettledTotal(BigDecimal pendingSettledTotal) { this.pendingSettledTotal = pendingSettledTotal; }
    public BigDecimal getLateTotal() { return lateTotal; }
    public void setLateTotal(BigDecimal lateTotal) { this.lateTotal = lateTotal; }
    public BigDecimal getTransferableAmount() { return transferableAmount; }
    public void setTransferableAmount(BigDecimal transferableAmount) { this.transferableAmount = transferableAmount; }
    public BigDecimal getTransferredAmount() { return transferredAmount; }
    public void setTransferredAmount(BigDecimal transferredAmount) { this.transferredAmount = transferredAmount; }
    public String getTransferReceiptId() { return transferReceiptId; }
    public void setTransferReceiptId(String transferReceiptId) { this.transferReceiptId = transferReceiptId; }
    public int getAttemptCount() { return attemptCount; }
    public void setAttemptCount(int attemptCount) { this.attemptCount = attemptCount; }
    public String getFailReason() { return failReason; }
    public void setFailReason(String failReason) { this.failReason = failReason; }
    public Instant getReportedAt() { return reportedAt; }
    public void setReportedAt(Instant reportedAt) { this.reportedAt = reportedAt; }
    public Instant getIssuedAt() { return issuedAt; }
    public void setIssuedAt(Instant issuedAt) { this.issuedAt = issuedAt; }
    public Instant getTransferredAt() { return transferredAt; }
    public void setTransferredAt(Instant transferredAt) { this.transferredAt = transferredAt; }
    public Instant getClosedAt() { return closedAt; }
    public void setClosedAt(Instant closedAt) { this.closedAt = closedAt; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
}
