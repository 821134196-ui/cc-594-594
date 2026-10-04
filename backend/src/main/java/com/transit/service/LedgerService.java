package com.transit.service;

import com.transit.domain.Channel;
import com.transit.domain.TxnKind;
import com.transit.domain.TxnStatus;
import com.transit.entity.CardEntity;
import com.transit.entity.TransactionEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.Instant;

/**
 * 账本：唯一允许写交易流水与卡片余额的地方。
 * 不变量：SETTLED 流水金额之和 == 卡片 balanceCents；PENDING 不触碰余额。
 */
@ApplicationScoped
public class LedgerService {

    /** 记一笔 PENDING 在途消费（闸机已记录，清算未到），不动余额 */
    @Transactional
    public TransactionEntity recordPendingFare(CardEntity card, long amountCents, String eventKey,
                                               Instant occurredAt, String deviceId, Long caseId, String note) {
        TransactionEntity t = base(card, TxnKind.FARE, -Math.abs(amountCents), eventKey, occurredAt, caseId, note);
        t.status = TxnStatus.PENDING;
        t.channel = Channel.GATE;
        t.deviceId = deviceId;
        t.persist();
        return t;
    }

    /** 记一笔已清算账并立即反映到余额 */
    @Transactional
    public TransactionEntity recordSettled(CardEntity card, TxnKind kind, long signedAmountCents,
                                           Channel channel, String eventKey, Instant occurredAt,
                                           String receiptNo, String deviceId, Long linkedTxnId,
                                           Long caseId, String note) {
        TransactionEntity t = base(card, kind, signedAmountCents, eventKey, occurredAt, caseId, note);
        t.status = TxnStatus.SETTLED;
        t.channel = channel;
        t.settledAt = Instant.now();
        t.receiptNo = receiptNo;
        t.deviceId = deviceId;
        t.linkedTxnId = linkedTxnId;
        t.persist();
        card.balanceCents += signedAmountCents;
        return t;
    }

    /** 把既有 PENDING 消费推进为 SETTLED（状态推进，金额/原流水不动），余额随之扣减 */
    @Transactional
    public TransactionEntity settlePending(TransactionEntity t, String receiptNo) {
        if (t.status != TxnStatus.PENDING) {
            throw new IllegalStateException("流水已清算，不能重复清算: " + t.eventKey);
        }
        t.status = TxnStatus.SETTLED;
        t.settledAt = Instant.now();
        if (receiptNo != null) t.receiptNo = receiptNo;
        CardEntity card = CardEntity.findById(t.cardId);
        card.balanceCents += t.signedAmountCents; // signedAmountCents 为负
        return t;
    }

    private TransactionEntity base(CardEntity card, TxnKind kind, long signedAmountCents,
                                   String eventKey, Instant occurredAt, Long caseId, String note) {
        TransactionEntity t = new TransactionEntity();
        t.cardId = card.id;
        t.kind = kind;
        t.signedAmountCents = signedAmountCents;
        t.eventKey = eventKey;
        t.occurredAt = occurredAt != null ? occurredAt : Instant.now();
        t.caseId = caseId;
        t.note = note;
        return t;
    }

    public long sumSettled(long cardId) {
        return TransactionEntity.<TransactionEntity>list("cardId", cardId).stream()
                .filter(t -> t.status == TxnStatus.SETTLED)
                .mapToLong(t -> t.signedAmountCents).sum();
    }

    public long sumPendingFares(long cardId) {
        return TransactionEntity.<TransactionEntity>list(
                        "cardId = ?1 and status = ?2 and kind = ?3",
                        cardId, TxnStatus.PENDING, TxnKind.FARE).stream()
                .mapToLong(t -> -t.signedAmountCents).sum(); // 返回正数金额
    }

    public long countPendingFares(long cardId) {
        return TransactionEntity.count("cardId = ?1 and status = ?2 and kind = ?3",
                cardId, TxnStatus.PENDING, TxnKind.FARE);
    }
}
