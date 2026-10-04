package com.buscard.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 交通卡。
 * status:
 *  ACTIVE    正常
 *  STOLEN    已挂失，停用中（等待在途消费清算）
 *  REPLACED  已完成余额转出（旧卡终态）
 *  BLOCKED   新卡再次挂失后的停用态（可继续被挂失/换卡）
 */
@Entity
@Table(name = "cards")
public class Card {

    @Id
    private String id;

    @Column(nullable = false)
    private String holderName;

    /** 身份核验凭证（演示用：证件号，服务端做哈希比对） */
    @Column(nullable = false)
    private String idHash;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal balance = BigDecimal.ZERO;

    @Column(nullable = false)
    private String status = "ACTIVE";

    private Instant reportedAt;

    @Version
    private long version;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getHolderName() { return holderName; }
    public void setHolderName(String holderName) { this.holderName = holderName; }
    public String getIdHash() { return idHash; }
    public void setIdHash(String idHash) { this.idHash = idHash; }
    public BigDecimal getBalance() { return balance; }
    public void setBalance(BigDecimal balance) { this.balance = balance; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getReportedAt() { return reportedAt; }
    public void setReportedAt(Instant reportedAt) { this.reportedAt = reportedAt; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
}
