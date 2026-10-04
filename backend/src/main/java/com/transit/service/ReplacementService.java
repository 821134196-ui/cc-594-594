package com.transit.service;

import com.transit.domain.*;
import com.transit.entity.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;

/**
 * 补卡主流程服务。
 *
 * 流程：身份核验 -> 停用旧卡(LOST) -> 制新卡 -> 等待在途消费清算 -> 确定可转余额 -> 配对转账
 * （旧卡 TRANSFER_OUT 与新卡 TRANSFER_IN 同事务、同金额）-> 旧卡终态 REPLACED。
 *
 * 记账纪律：
 *  - 卡片 balanceCents == 该卡全部 SETTLED 流水带符号之和；PENDING 不触碰余额。
 *  - 旧卡原流水永不修改；在途消费只做 PENDING->SETTLED 推进。
 *  - 晚到（转账后才清算）的旧卡消费：
 *      旧卡加一对 (FARE -, LATE_OFFSET +) 挂平，余额不漂；
 *      新卡资金链当前持有卡加 LATE_CLAWBACK - 承担真实成本；
 *      LateAdjustment 记录差额处理过程，追扣不成就挂 PENDING_CLAWBACK，记录不丢。
 *  - 一张旧卡同时只允许一个有效案件、关联一张新卡；失败案件保留并可复活重试。
 */
@ApplicationScoped
public class ReplacementService {

    @Inject CardFactoryService cardFactory;
    @Inject IdHasher idHasher;
    @Inject LedgerService ledger;
    @Inject CaseEventRecorder events;
    @Inject FailureRecorder failures;

    private volatile boolean failNextTransfer = false;

    /** 让下一次转账失败（演示：补卡失败也要保存处理记录） */
    public void armTransferFailure() { this.failNextTransfer = true; }

    // ---------------------------------------------------------------- 受理挂失

    @Transactional
    public ReplacementCaseEntity reportLoss(String cardNo, String idNo, String agentId) {
        CardEntity card = CardEntity.requireByNo(cardNo);
        if (!idHasher.matches(idNo, card.idHash)) {
            throw new ServiceException(401, "身份核验失败：证件号与持卡人不匹配，拒绝挂失");
        }
        if (card.status == CardStatus.REPLACED) {
            throw new ServiceException(409, "该卡已完成补卡并永久停用，不能重复受理（请对新卡办理）");
        }

        ReplacementCaseEntity existing = ReplacementCaseEntity.activeByOldCard(card.id);
        if (existing != null) {
            throw new ServiceException(409, "该卡已存在进行中的补卡案件 " + existing.caseNo
                    + "：一张旧卡只能关联一张有效新卡，禁止重复补卡");
        }

        long settledBalance = card.balanceCents;
        long pending = ledger.sumPendingFares(card.id);

        ReplacementCaseEntity failed = ReplacementCaseEntity.find(
                "oldCardId = ?1 and status = ?2", card.id, CaseStatus.FAILED).firstResult();
        if (failed != null) {
            return reopen(failed, card, agentId);
        }

        if (card.status == CardStatus.LOST) {
            throw new ServiceException(409, "该卡已挂失停用，但没有可恢复的案件记录");
        }

        card.status = CardStatus.LOST;
        card.blockedAt = Instant.now();

        ReplacementCaseEntity c = new ReplacementCaseEntity();
        c.caseNo = nextCaseNo();
        c.oldCardId = card.id;
        c.status = CaseStatus.OLD_CARD_BLOCKED;
        c.agentId = agentId;
        c.oldCardBalanceAtReportCents = settledBalance;
        c.pendingFaresAtReportCents = pending;
        c.createdAt = Instant.now();
        c.blockedAt = card.blockedAt;
        c.persist();
        events.event(c, "REPORT_ACCEPTED",
                "客服 " + agentId + " 受理挂失，身份核验通过（证件 " + IdHasher.maskIdNo(idNo) + "）");
        events.event(c, "OLD_CARD_BLOCKED",
                "旧卡 " + card.cardNo + " 已即时停用；受理时已清算余额 "
                        + money(settledBalance) + " 元，已登记在途消费 " + money(pending)
                        + " 元（在途未实际扣款）", "CARD", card.id);
        return c;
    }

    private ReplacementCaseEntity reopen(ReplacementCaseEntity c, CardEntity card, String agentId) {
        if (card.status == CardStatus.ACTIVE) {
            card.status = CardStatus.LOST;
            card.blockedAt = Instant.now();
        }
        c.status = c.newCardId == null
                ? CaseStatus.OLD_CARD_BLOCKED
                : (ledger.countPendingFares(card.id) > 0
                    ? CaseStatus.WAITING_CLEARING : CaseStatus.READY_TO_TRANSFER);
        c.failureReason = null;
        events.event(c, "CASE_REOPENED",
                "客服 " + agentId + " 重新发起办理，沿用原案件与全部历史记录（含失败尝试）；旧卡仍停用");
        return c;
    }

    /** 操作结果：模拟失败时 failed=true 且记录已提交，不抛异常、不回滚 */
    public static class OpResult {
        public final ReplacementCaseEntity entity;
        public final boolean failed;
        public final String error;
        OpResult(ReplacementCaseEntity e, boolean failed, String error) {
            this.entity = e; this.failed = failed; this.error = error;
        }
        static OpResult ok(ReplacementCaseEntity e) { return new OpResult(e, false, null); }
        static OpResult fail(ReplacementCaseEntity e, String err) { return new OpResult(e, true, err); }
    }

    // ---------------------------------------------------------------- 制新卡

    @Transactional
    public OpResult issueNewCard(String caseNo) {
        ReplacementCaseEntity c = requireCase(caseNo);
        if (c.status == CaseStatus.TRANSFERRED) {
            throw new ServiceException(409, "案件已完成，无需制卡");
        }
        if (c.status != CaseStatus.OLD_CARD_BLOCKED && c.status != CaseStatus.FAILED) {
            throw new ServiceException(409, "当前状态 " + c.status + " 不允许制卡（必须先确认旧卡停用）");
        }
        if (c.newCardId != null) {
            throw new ServiceException(409, "新卡已制作：一张旧卡只能关联一张有效新卡");
        }
        CardEntity old = CardEntity.findById(c.oldCardId);
        try {
            CardEntity n = cardFactory.produceForHolder(old.holderName, old.idHash, "CARD_OFFICE_01");
            c.newCardId = n.id;
            c.newCardIssuedAt = Instant.now();
            boolean waiting = ledger.countPendingFares(old.id) > 0;
            c.status = waiting ? CaseStatus.WAITING_CLEARING : CaseStatus.READY_TO_TRANSFER;
            events.event(c, "NEW_CARD_ISSUED",
                    "制卡服务发放入新卡 " + n.cardNo + "（设备 " + n.producedBy
                            + "），初始余额 0.00 元，等待余额转移", "CARD", n.id);
            if (waiting) {
                events.event(c, "WAITING_CLEARING",
                        "尚有 " + ledger.countPendingFares(old.id) + " 笔在途消费未清算，余额仍在等待清算，"
                                + "暂不可转；当前参考可转额 = 已清算余额 " + money(old.balanceCents)
                                + " - 在途预留 " + money(ledger.sumPendingFares(old.id))
                                + " = " + money(Math.max(0, old.balanceCents - ledger.sumPendingFares(old.id)))
                                + " 元（最终以清算完成为准）");
            } else {
                events.event(c, "READY_TO_TRANSFER",
                        "无在途消费，可转余额确定为 " + money(old.balanceCents) + " 元");
            }
            return OpResult.ok(c);
        } catch (CardFactoryService.CardFactoryException e) {
            failures.recordIssueFailure(c, e.getMessage());
            return OpResult.fail(c, e.getMessage());
        }
    }

    // ---------------------------------------------------------------- 余额转移

    @Transactional
    public OpResult transfer(String caseNo) {
        ReplacementCaseEntity c = requireCase(caseNo);
        if (c.status == CaseStatus.TRANSFERRED) {
            throw new ServiceException(409, "余额已转移完成，禁止重复转账");
        }
        if (c.status != CaseStatus.READY_TO_TRANSFER && c.status != CaseStatus.FAILED) {
            throw new ServiceException(409, "当前状态 " + c.status + "：尚未完成制卡或仍在等待清算，不能转账");
        }
        if (c.newCardId == null) {
            throw new ServiceException(409, "尚未制发新卡");
        }
        long pending = ledger.countPendingFares(c.oldCardId);
        if (pending > 0) {
            throw new ServiceException(409, "仍有 " + pending + " 笔在途消费未清算，余额仍在等待清算，不能转账");
        }
        CardEntity old = CardEntity.findById(c.oldCardId);
        CardEntity neo = CardEntity.findById(c.newCardId);
        if (neo.status != CardStatus.ACTIVE) {
            throw new ServiceException(409, "新卡状态为 " + neo.status + "，无法入账（处理记录保留，未转移资金不丢）");
        }

        long amount = Math.max(0L, old.balanceCents);
        int attempt = (int) BalanceTransferEntity.count("caseId", c.id) + 1;

        if (failNextTransfer) {
            failNextTransfer = false;
            String note = "转账通道异常（模拟）：旧卡未扣款、新卡未入账，资金守恒，可原样重试";
            failures.recordTransferFailure(c, attempt, amount, note);
            return OpResult.fail(c, note);
        }

        TransactionEntity out = ledger.recordSettled(old, TxnKind.TRANSFER_OUT, -amount,
                Channel.SYSTEM, null, Instant.now(), null, null, null, c.id,
                "补卡转出：案件 " + c.caseNo);
        TransactionEntity in = ledger.recordSettled(neo, TxnKind.TRANSFER_IN, amount,
                Channel.SYSTEM, null, Instant.now(), null, null, out.id, c.id,
                "补卡转入：案件 " + c.caseNo);
        out.linkedTxnId = in.id;

        BalanceTransferEntity rec = new BalanceTransferEntity();
        rec.caseId = c.id;
        rec.attemptNo = attempt;
        rec.status = TransferStatus.COMPLETED;
        rec.amountCents = amount;
        rec.outTxnId = out.id;
        rec.inTxnId = in.id;
        rec.attemptedAt = Instant.now();
        rec.note = "旧卡转出 " + money(amount) + " 元 = 新卡转入 " + money(amount)
                + " 元（配对流水 #" + out.id + "/#" + in.id + "）";
        rec.persist();

        c.status = CaseStatus.TRANSFERRED;
        c.transferredAt = Instant.now();
        c.closedAt = c.transferredAt;
        c.transferredOutCents += amount;
        c.transferredInCents += amount;
        old.status = CardStatus.REPLACED;

        long settledFares = c.oldCardBalanceAtReportCents - amount;
        events.event(c, "TRANSFER_DONE",
                "余额已转入新卡 " + neo.cardNo + "：可转余额 " + money(amount)
                        + " 元 = 受理快照 " + money(c.oldCardBalanceAtReportCents)
                        + " - 已清算在途消费 " + money(Math.max(0, settledFares))
                        + " 元；旧卡转出与新卡转入金额严格一致（配对流水，可逐笔核对）",
                "TRANSFER", rec.id);
        events.event(c, "OLD_CARD_TERMINATED",
                "旧卡 " + old.cardNo + " 转为永久停用（REPLACED），原流水全部保留可核",
                "CARD", old.id);
        return OpResult.ok(c);
    }

    // ---------------------------------------------------------------- 晚到差额

    /**
     * 转账完成后才到达的旧卡消费。
     * 旧卡：原 FARE 已随清算 SETTLED（余额被扣成负），这里补一笔 LATE_OFFSET +X 挂平；
     * 新卡资金链：当前持有卡 LATE_CLAWBACK -X，真实承担；
     * 挂账记录逐笔保存，追扣不成就保留 PENDING_CLAWBACK。
     */
    @Transactional
    public LateAdjustmentEntity registerLateFare(ReplacementCaseEntity c, TransactionEntity fare,
                                                 long amountCents, String receiptNo) {
        CardEntity old = CardEntity.findById(c.oldCardId);
        TransactionEntity offset = ledger.recordSettled(old, TxnKind.LATE_OFFSET, amountCents,
                Channel.CLEARING, null, Instant.now(), receiptNo, null, fare.id, c.id,
                "晚到消费资金平移（旧卡侧挂平）：对应原乘车流水 #" + fare.id
                        + "，乘车时间 " + fare.occurredAt + "，回执 " + receiptNo);

        LateAdjustmentEntity adj = new LateAdjustmentEntity();
        adj.caseId = c.id;
        adj.oldCardId = c.oldCardId;
        adj.newCardId = c.newCardId;
        adj.originalFareTxnId = fare.id;
        adj.offsetTxnId = offset.id;
        adj.amountCents = amountCents;
        adj.status = AdjustmentStatus.PENDING_CLAWBACK;
        adj.discoveredAt = Instant.now();
        adj.note = "晚到交易：清算回执 " + receiptNo + " 在余额转移之后到达；原 FARE 流水 #"
                + fare.id + " 保留未改，旧卡以 LATE_OFFSET 挂平，需由新卡方追回 "
                + money(amountCents) + " 元";
        adj.persist();
        events.event(c, "LATE_FARE_DISCOVERED",
                "发现晚到消费 " + money(amountCents) + " 元（乘车于 " + fare.occurredAt
                        + "，回执 " + receiptNo + "）：旧卡原流水 #" + fare.id
                        + " 保留不抹账，登记待处理差额 #" + adj.id, "ADJUSTMENT", adj.id);
        tryClawback(adj);
        return adj;
    }

    /** 重试案件下所有挂账的追扣 */
    @Transactional
    public int retryClawbacks(String caseNo) {
        ReplacementCaseEntity c = requireCase(caseNo);
        var pendings = LateAdjustmentEntity.<LateAdjustmentEntity>list(
                "caseId = ?1 and status = ?2", c.id, AdjustmentStatus.PENDING_CLAWBACK);
        int n = 0;
        for (LateAdjustmentEntity adj : pendings) if (tryClawback(adj)) n++;
        return n;
    }

    private boolean tryClawback(LateAdjustmentEntity adj) {
        ReplacementCaseEntity c = ReplacementCaseEntity.findById(adj.caseId);
        CardEntity start = CardEntity.findById(adj.newCardId);
        CardEntity holder = currentFundHolder(start);
        if (holder == null || holder.status != CardStatus.ACTIVE) {
            events.event(c, "CLAWBACK_WAITING",
                    "差额 " + money(adj.amountCents) + " 元暂未追回：新卡非可用状态（已再次挂失/停用），"
                            + "挂账保留，未完成的转移不丢", "ADJUSTMENT", adj.id);
            return false;
        }
        if (holder.balanceCents < adj.amountCents) {
            events.event(c, "CLAWBACK_WAITING",
                    "差额 " + money(adj.amountCents) + " 元暂未追回：当前持卡 " + holder.cardNo
                            + " 余额仅 " + money(holder.balanceCents) + " 元，挂账保留，可稍后重试",
                    "ADJUSTMENT", adj.id);
            return false;
        }
        TransactionEntity original = TransactionEntity.findById(adj.originalFareTxnId);
        boolean chained = !holder.id.equals(adj.newCardId);
        TransactionEntity claw = ledger.recordSettled(holder, TxnKind.LATE_CLAWBACK, -adj.amountCents,
                Channel.SYSTEM, null, Instant.now(), original.receiptNo, null,
                adj.originalFareTxnId, adj.caseId,
                "晚到消费追扣：对应旧卡原流水 #" + adj.originalFareTxnId + "（回执 "
                        + original.receiptNo + "），案件 " + c.caseNo
                        + (chained ? "；新卡已再次挂失，沿补卡链追到当前持卡" : ""));
        adj.status = AdjustmentStatus.RESOLVED;
        adj.resolvedAt = Instant.now();
        adj.clawbackTxnId = claw.id;
        c.lateClawbackCents += adj.amountCents;
        events.event(c, "CLAWBACK_DONE",
                "晚到差额 " + money(adj.amountCents) + " 元已从 " + holder.cardNo
                        + " 追回（追扣流水 #" + claw.id + "，与原消费流水 #"
                        + adj.originalFareTxnId + " 配对，旧卡挂平流水 #" + adj.offsetTxnId + "）",
                "ADJUSTMENT", adj.id);
        return true;
    }

    /** 沿“旧卡->新卡”的已完成补卡链找到当前持有资金的卡 */
    private CardEntity currentFundHolder(CardEntity card) {
        CardEntity cur = card;
        java.util.Set<Long> guard = new java.util.HashSet<>();
        while (cur != null && cur.status == CardStatus.REPLACED && guard.add(cur.id)) {
            ReplacementCaseEntity next = ReplacementCaseEntity.find(
                    "oldCardId = ?1 and status = ?2", cur.id, CaseStatus.TRANSFERRED).firstResult();
            if (next == null || next.newCardId == null) break;
            cur = CardEntity.findById(next.newCardId);
        }
        return cur;
    }

    /** 清算推进后重算案件状态（在途清零 -> READY_TO_TRANSFER） */
    @Transactional
    public void refreshCaseState(ReplacementCaseEntity c) {
        if (c.status == CaseStatus.WAITING_CLEARING
                && ledger.countPendingFares(c.oldCardId) == 0) {
            c.status = CaseStatus.READY_TO_TRANSFER;
            CardEntity old = CardEntity.findById(c.oldCardId);
            events.event(c, "CLEARING_COMPLETE",
                    "全部在途消费已清算，余额已结束等待清算：可转余额最终确定为 "
                            + money(old.balanceCents) + " 元（= 受理快照 "
                            + money(c.oldCardBalanceAtReportCents) + " - 受理后清算扣款 "
                            + money(c.oldCardBalanceAtReportCents - old.balanceCents)
                            + " 元），可执行转账");
        }
    }

    // ---------------------------------------------------------------- 杂项

    @Transactional
    public ReplacementCaseEntity requireCase(String caseNo) {
        ReplacementCaseEntity c = ReplacementCaseEntity.find("caseNo", caseNo).firstResult();
        if (c == null) throw new ServiceException(404, "案件不存在: " + caseNo);
        return c;
    }

    private String nextCaseNo() {
        long max = ReplacementCaseEntity.<ReplacementCaseEntity>listAll().stream()
                .mapToLong(x -> {
                    try { return Long.parseLong(x.caseNo.substring(1)); }
                    catch (Exception e) { return 0; }
                }).max().orElse(1000);
        return "B" + String.format("%06d", max + 1);
    }

    static String money(long cents) {
        long a = Math.abs(cents);
        return (cents < 0 ? "-" : "") + (a / 100) + "." + String.format("%02d", a % 100);
    }
}
