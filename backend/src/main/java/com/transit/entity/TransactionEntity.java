package com.transit.entity;

import com.transit.domain.Channel;
import com.transit.domain.TxnKind;
import com.transit.domain.TxnStatus;
import com.transit.entity.BaseEntity;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * 卡交易流水。
 * 铁律：流水一旦写入，amountCents / kind / 发生时间永不修改；
 * 在途消费只允许 PENDING -> SETTLED 的状态推进，晚到消费也保留独立原流水，
 * 差额通过新增调整分录处理，绝不篡改旧交易抹平余额。
 *
 * signedAmountCents 带符号：正=入账(充值/转入/追回冲平)，负=扣款(消费/转出/追扣)。
 */
@Entity
@Table(name = "txn", uniqueConstraints = {
        @UniqueConstraint(name = "uk_txn_event_key", columnNames = "event_key")
}, indexes = {
        @Index(name = "idx_txn_card", columnList = "card_id"),
        @Index(name = "idx_txn_status", columnList = "status")
})
public class TransactionEntity extends BaseEntity {

    @Column(nullable = false)
    public Long cardId;

    /** 闸机/清算行幂等键：GATE 事件号或清算回执内的行事件号；系统账为 null */
    @Column(length = 64)
    public String eventKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public TxnKind kind;

    /** 带符号金额（分） */
    @Column(nullable = false)
    public long signedAmountCents;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public TxnStatus status = TxnStatus.SETTLED;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public Channel channel;

    /** 设备侧业务发生时间（晚到交易可能远早于入库时间） */
    @Column(nullable = false)
    public Instant occurredAt;

    public Instant settledAt;

    @Column(length = 32)
    public String deviceId;

    @Column(length = 48)
    public String receiptNo;

    /** 关联流水：转出<->转入；晚到扣款<->追扣/冲平 */
    public Long linkedTxnId;

    /** 关联补卡案件 */
    public Long caseId;

    @Column(length = 256)
    public String note;
}
