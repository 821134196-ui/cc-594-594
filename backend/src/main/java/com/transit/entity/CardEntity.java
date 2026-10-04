package com.transit.entity;

import com.transit.domain.CardStatus;
import com.transit.entity.BaseEntity;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * 公交卡。balanceCents 是“已清算余额”，与该卡全部已清算流水金额之和严格相等；
 * 在途(PENDING)消费不改动余额，只通过可转余额公式预留。
 */
@Entity
@Table(name = "card", uniqueConstraints = {
        @UniqueConstraint(name = "uk_card_no", columnNames = "card_no")
})
public class CardEntity extends BaseEntity {

    /** 16 位卡号，如 6200000000001001 */
    @Column(nullable = false, length = 16)
    public String cardNo;

    @Column(nullable = false)
    public String holderName;

    /** 证件号（演示用后 4 位参与核验，全量入库为简单哈希） */
    @Column(nullable = false, length = 64)
    public String idHash;

    @Column(nullable = false)
    public long balanceCents;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public CardStatus status = CardStatus.ACTIVE;

    public Instant issuedAt;
    public Instant blockedAt;

    /** 制卡设备/批次来源（制卡服务模拟） */
    @Column(length = 32)
    public String producedBy;

    public static CardEntity byNo(String cardNo) {
        return find("cardNo", cardNo).firstResult();
    }

    public static CardEntity requireByNo(String cardNo) {
        CardEntity c = byNo(cardNo);
        if (c == null) throw new com.transit.service.ServiceException(404, "卡号不存在: " + cardNo);
        return c;
    }
}
