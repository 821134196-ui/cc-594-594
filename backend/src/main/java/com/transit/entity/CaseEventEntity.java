package com.transit.entity;

import com.transit.entity.BaseEntity;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * 案件事件流（进度时间轴）。每次状态推进、拒绝、失败、清算、转移、追扣都追加一行，
 * 乘客页面展示办理进度，客服据此追溯每笔扣款和转移依据。只增不改。
 */
@Entity
@Table(name = "case_event", indexes = {
        @Index(name = "idx_event_case", columnList = "case_id")
})
public class CaseEventEntity extends BaseEntity {

    @Column(nullable = false)
    public Long caseId;

    /** 机器可读事件码，如 BLOCKED / GATE_REJECTED / RECEIPT_DUPLICATE / TRANSFER_DONE */
    @Column(nullable = false, length = 48)
    public String code;

    @Column(nullable = false)
    public String detail;

    @Column(length = 64)
    public String refType;   // TXN / RECEIPT / TRANSFER / ADJUSTMENT / CARD
    public Long refId;

    public Instant at;
}
