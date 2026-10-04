package com.transit.service;

import com.transit.domain.Channel;
import com.transit.domain.ReceiptStatus;
import com.transit.domain.TxnKind;
import com.transit.domain.TxnStatus;
import com.transit.entity.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 清算回执处理（本地清算中心模拟）。
 *
 * 幂等：receiptNo 唯一登记；重复回执直接返回 DUPLICATE 与首次结果，
 * 不再结算任何流水、不产生任何扣款或转移 —— 重复清算不多转一分钱。
 * 行级也按 eventKey 幂等（同一笔乘车在多张回执出现只认第一次）。
 *
 * 晚到：
 *  - 行对应已有 PENDING 流水：推进 SETTLED；
 *  - 行是全新离线消费（此前闸机从未上报）：补建原 FARE 流水（保留原始 occurredAt）并清算；
 *  - 若案件已转账完成：原流水保留 + 旧卡 LATE_OFFSET 挂平 + 新卡追扣（见 ReplacementService）。
 */
@ApplicationScoped
public class ClearingService {

    @Inject LedgerService ledger;
    @Inject ReplacementService replacements;
    @Inject CaseEventRecorder events;

    public static class Line {
        public String eventKey;
        public long amountCents;
        public Instant occurredAt;
        public String deviceId;
    }

    public static class LineResult {
        public String eventKey;
        public long amountCents;
        public String action;     // SETTLED_PENDING / NEW_FARE_SETTLED / LATE_FARE / DUPLICATE_LINE
        public Long txnId;
        public Long adjustmentId;
        public boolean clawedBack;
    }

    public static class ReceiptResult {
        public String receiptNo;
        public String caseNo;
        public ReceiptStatus status;
        public int settledLineCount;
        public long settledAmountCents;
        public List<LineResult> lines = new ArrayList<>();
        public String duplicateOfNote;
        /** 处理后案件视角的可转/已转余额（分），便于前端与演示说明 */
        public Long oldCardBalanceCents;
        public Long pendingFareCents;
        public String caseStatus;
    }

    @Transactional
    public ReceiptResult acceptReceipt(String caseNo, String receiptNo, List<Line> lines) {
        ReplacementCaseEntity c = replacements.requireCase(caseNo);
        CardEntity old = CardEntity.findById(c.oldCardId);

        ClearingReceiptEntity existing = ClearingReceiptEntity.find("receiptNo", receiptNo).firstResult();
        ReceiptResult r = new ReceiptResult();
        r.receiptNo = receiptNo;
        r.caseNo = c.caseNo;
        if (existing != null) {
            r.status = ReceiptStatus.DUPLICATE;
            r.settledLineCount = 0;
            r.settledAmountCents = 0;
            r.duplicateOfNote = "回执 " + receiptNo + " 已于 " + existing.firstReceivedAt
                    + " 处理过（首次结算 " + existing.settledLineCount + " 行 / "
                    + ReplacementService.money(existing.settledAmountCents)
                    + " 元）；本次重复回执被忽略，未重复扣款、未重复转移";
            events.event(c, "RECEIPT_DUPLICATE", r.duplicateOfNote, "RECEIPT", existing.id);
            fillBalances(r, c, old);
            return r;
        }

        Instant now = Instant.now();
        long amountSum = 0;
        int lineCount = 0;

        for (Line line : lines) {
            LineResult lr = new LineResult();
            lr.eventKey = line.eventKey;
            lr.amountCents = Math.abs(line.amountCents);

            TransactionEntity tx = line.eventKey == null ? null
                    : TransactionEntity.find("eventKey", line.eventKey).firstResult();

            if (tx != null && tx.status == TxnStatus.SETTLED) {
                lr.action = "DUPLICATE_LINE";
                lr.txnId = tx.id;
                r.lines.add(lr);
                continue;
            }

            if (tx == null) {
                // 全新离线消费：按设备原始时间补建 FARE 原流水（在途），随后立即清算
                tx = ledger.recordPendingFare(old, lr.amountCents, line.eventKey,
                        line.occurredAt != null ? line.occurredAt : now,
                        line.deviceId != null ? line.deviceId : "OFFLINE_BATCH",
                        c.id, "离线批次补录原乘车流水（回执 " + receiptNo + "）");
                lr.action = "NEW_FARE_SETTLED";
            } else {
                lr.action = "SETTLED_PENDING";
            }

            ledger.settlePending(tx, receiptNo);
            lr.txnId = tx.id;
            amountSum += lr.amountCents;
            lineCount++;

            boolean alreadyTransferred = c.transferredAt != null;
            if (alreadyTransferred) {
                // 晚到：余额已转走，旧卡这笔扣款不能由旧卡承担 -> 挂平 + 向新卡追
                LateAdjustmentEntity adj = replacements.registerLateFare(c, tx, lr.amountCents, receiptNo);
                lr.adjustmentId = adj.id;
                lr.clawedBack = adj.status == com.transit.domain.AdjustmentStatus.RESOLVED;
                lr.action = "LATE_FARE";
            }
            r.lines.add(lr);
        }

        if (!alreadyTransferred(c)) {
            replacements.refreshCaseState(c);
        }

        ClearingReceiptEntity rec = new ClearingReceiptEntity();
        rec.receiptNo = receiptNo;
        rec.caseId = c.id;
        rec.status = ReceiptStatus.ACCEPTED;
        rec.settledLineCount = lineCount;
        rec.settledAmountCents = amountSum;
        rec.receivedAt = now;
        rec.firstReceivedAt = now;
        rec.note = "首次受理，结算 " + lineCount + " 行，金额 " + ReplacementService.money(amountSum) + " 元";
        rec.persist();

        r.status = ReceiptStatus.ACCEPTED;
        r.settledLineCount = lineCount;
        r.settledAmountCents = amountSum;

        String summary = "清算回执 " + receiptNo + " 受理：结算 " + lineCount + " 笔共 "
                + ReplacementService.money(amountSum) + " 元";
        if (r.lines.stream().anyMatch(l -> "LATE_FARE".equals(l.action))) {
            long late = r.lines.stream().filter(l -> "LATE_FARE".equals(l.action))
                    .mapToLong(l -> l.amountCents).sum();
            summary += "；其中 " + ReplacementService.money(late)
                    + " 元为晚到交易（余额已转），原流水保留、登记差额并向新卡追扣，不修改旧交易";
        }
        events.event(c, "RECEIPT_ACCEPTED", summary, "RECEIPT", rec.id);

        fillBalances(r, c, CardEntity.findById(c.oldCardId));
        return r;
    }

    private boolean alreadyTransferred(ReplacementCaseEntity c) {
        return c.transferredAt != null;
    }

    private void fillBalances(ReceiptResult r, ReplacementCaseEntity c, CardEntity old) {
        r.oldCardBalanceCents = old.balanceCents;
        r.pendingFareCents = ledger.sumPendingFares(old.id);
        r.caseStatus = c.status.name();
    }
}
