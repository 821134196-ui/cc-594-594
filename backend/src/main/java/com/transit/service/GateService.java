package com.transit.service;

import com.transit.entity.CardEntity;
import com.transit.entity.ReplacementCaseEntity;
import com.transit.entity.TransactionEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

/**
 * 本地“闸机”模拟。
 * 刷卡规则：只有 ACTIVE 卡放行并写一笔 PENDING 在途消费；
 * LOST（挂失停用中）/ REPLACED（已补卡永久停用）一律拒绝，拒绝事件进入案件进度。
 */
@ApplicationScoped
public class GateService {

    @Inject LedgerService ledger;
    @Inject CaseEventRecorder events;

    public static class SwipeResult {
        public boolean allowed;
        public String cardNo;
        public String cardStatus;
        public long fareCents;
        public String eventKey;
        public String message;
        public String timestamp;
    }

    @Transactional
    public SwipeResult swipe(String cardNo, long fareCents, String deviceId, String eventKey) {
        CardEntity card = CardEntity.requireByNo(cardNo);
        SwipeResult r = new SwipeResult();
        r.cardNo = cardNo;
        r.cardStatus = card.status.name();
        r.fareCents = fareCents;
        r.eventKey = eventKey;
        r.timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now());

        if (card.status != com.transit.domain.CardStatus.ACTIVE) {
            r.allowed = false;
            String reason = card.status == com.transit.domain.CardStatus.LOST
                    ? "旧卡已挂失停用，正在补卡流程中，请使用新卡"
                    : "旧卡已补卡并永久停用（余额已转至新卡），此卡作废";
            r.message = "拒绝通行：" + reason;
            ReplacementCaseEntity c = ReplacementCaseEntity.find(
                    "oldCardId", card.id).firstResult();
            if (c != null) {
                events.event(c, "GATE_REJECTED",
                        "闸机 " + deviceId + " 尝试用旧卡 " + cardNo + " 刷卡（" + r.timestamp
                                + "），被拒绝：" + reason, "CARD", card.id);
            }
            return r;
        }

        if (TransactionEntity.find("eventKey", eventKey).firstResult() != null) {
            // 闸机重发同一事件：幂等，不重复记账，当作已处理
            r.allowed = true;
            r.message = "重复闸机事件，已幂等忽略，未重复扣款";
            return r;
        }

        TransactionEntity t = ledger.recordPendingFare(card, fareCents, eventKey,
                Instant.now(), deviceId, null,
                "闸机 " + deviceId + " 上车扣费 " + ReplacementService.money(fareCents) + " 元（在途）");
        r.allowed = true;
        r.message = "放行：已登记在途消费 " + ReplacementService.money(fareCents)
                + " 元（流水 #" + t.id + "，待清算回执到达后实际扣款）";
        return r;
    }
}
