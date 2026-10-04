package com.transit.web;

import com.transit.domain.*;
import com.transit.entity.*;
import com.transit.service.IdHasher;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.inject.Inject;
import java.util.*;

/** 乘客 / 客服页面聚合查询：脱敏流水、余额、转移状态、进度时间轴、对账依据 */
@ApplicationScoped
public class CaseQueryService {

    @Inject IdHasher idHasher;

    public static class TxnView {
        public long id;
        public String cardNoMasked;
        public String side;           // OLD / NEW
        public String kind;
        public String kindLabel;
        public long signedAmountCents;
        public String amountText;
        public String status;
        public String channel;
        public String occurredAt;
        public String settledAt;
        public String receiptNo;
        public String deviceId;
        public String eventKey;
        public Long linkedTxnId;
        public String note;
    }

    public static class CardView {
        public long id;
        public String cardNo;
        public String cardNoMasked;
        public String holderName;
        public String status;
        public String statusLabel;
        public long balanceCents;
        public String balanceText;
        public long pendingFareCents;
        public long pendingFareCount;
        public String issuedAt;
        public String blockedAt;
        /** 已清算流水求和（必须等于 balanceCents，对账用） */
        public long settledLedgerSumCents;
    }

    public static class TransferView {
        public int attemptNo;
        public String status;
        public long amountCents;
        public String amountText;
        public String attemptedAt;
        public Long outTxnId;
        public Long inTxnId;
        public String note;
    }

    public static class AdjustmentView {
        public long id;
        public long amountCents;
        public String amountText;
        public String status;
        public long originalFareTxnId;
        public long offsetTxnId;
        public Long clawbackTxnId;
        public String discoveredAt;
        public String resolvedAt;
        public String note;
    }

    public static class EventView {
        public String code;
        public String detail;
        public String refType;
        public Long refId;
        public String at;
    }

    public static class ReceiptView {
        public String receiptNo;
        public String status;
        public int settledLineCount;
        public long settledAmountCents;
        public String receivedAt;
        public String note;
    }

    public static class CaseDetail {
        public String caseNo;
        public String status;
        public String statusLabel;
        public String agentId;
        public String createdAt;
        public String transferredAt;
        public long balanceAtReportCents;
        public long pendingAtReportCents;
        public long transferredOutCents;
        public long transferredInCents;
        public boolean transferBalanced;
        public long lateClawbackCents;
        public String failureReason;

        /** 资金阶段说明：WAITING 余额仍在等待清算 / TRANSFERRED 已转入新卡 等 */
        public String moneyPhase;

        public CardView oldCard;
        public CardView newCard;
        public List<TxnView> oldTxns = new ArrayList<>();
        public List<TxnView> newTxns = new ArrayList<>();
        public List<TransferView> transfers = new ArrayList<>();
        public List<AdjustmentView> adjustments = new ArrayList<>();
        public List<ReceiptView> receipts = new ArrayList<>();
        public List<EventView> events = new ArrayList<>();
    }

    @Transactional
    public List<Map<String, Object>> listCases() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ReplacementCaseEntity c : ReplacementCaseEntity.<ReplacementCaseEntity>listAll()) {
            CardEntity old = CardEntity.findById(c.oldCardId);
            CardEntity neo = c.newCardId == null ? null : CardEntity.findById(c.newCardId);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("caseNo", c.caseNo);
            m.put("status", c.status.name());
            m.put("statusLabel", label(c.status));
            m.put("oldCardNo", mask(old.cardNo));
            m.put("newCardNo", neo == null ? null : mask(neo.cardNo));
            m.put("holderName", old.holderName);
            m.put("createdAt", str(c.createdAt));
            out.add(m);
        }
        out.sort(Comparator.comparing(m -> (String) m.get("caseNo")));
        return out;
    }

    @Transactional
    public CaseDetail detail(String caseNo) {
        ReplacementCaseEntity c = ReplacementCaseEntity.find("caseNo", caseNo).firstResult();
        if (c == null) throw new com.transit.service.ServiceException(404, "案件不存在: " + caseNo);
        CaseDetail d = new CaseDetail();
        d.caseNo = c.caseNo;
        d.status = c.status.name();
        d.statusLabel = label(c.status);
        d.agentId = c.agentId;
        d.createdAt = str(c.createdAt);
        d.transferredAt = str(c.transferredAt);
        d.balanceAtReportCents = c.oldCardBalanceAtReportCents;
        d.pendingAtReportCents = c.pendingFaresAtReportCents;
        d.transferredOutCents = c.transferredOutCents;
        d.transferredInCents = c.transferredInCents;
        d.transferBalanced = c.transferredOutCents == c.transferredInCents;
        d.lateClawbackCents = c.lateClawbackCents;
        d.failureReason = c.failureReason;
        d.moneyPhase = phase(c);

        d.oldCard = card(c.oldCardId);
        d.newCard = c.newCardId == null ? null : card(c.newCardId);

        for (TransactionEntity t : txns(c.oldCardId)) d.oldTxns.add(txn(t, "OLD"));
        if (c.newCardId != null) for (TransactionEntity t : txns(c.newCardId)) d.newTxns.add(txn(t, "NEW"));

        for (BalanceTransferEntity b : BalanceTransferEntity.<BalanceTransferEntity>list("caseId", c.id)) {
            TransferView v = new TransferView();
            v.attemptNo = b.attemptNo;
            v.status = b.status.name();
            v.amountCents = b.amountCents;
            v.amountText = money(b.amountCents);
            v.attemptedAt = str(b.attemptedAt);
            v.outTxnId = b.outTxnId;
            v.inTxnId = b.inTxnId;
            v.note = b.note;
            d.transfers.add(v);
        }
        d.transfers.sort(Comparator.comparingInt(v -> v.attemptNo));

        for (LateAdjustmentEntity a : LateAdjustmentEntity.<LateAdjustmentEntity>list("caseId", c.id)) {
            AdjustmentView v = new AdjustmentView();
            v.id = a.id;
            v.amountCents = a.amountCents;
            v.amountText = money(a.amountCents);
            v.status = a.status.name();
            v.originalFareTxnId = a.originalFareTxnId;
            v.offsetTxnId = a.offsetTxnId == null ? 0 : a.offsetTxnId;
            v.clawbackTxnId = a.clawbackTxnId;
            v.discoveredAt = str(a.discoveredAt);
            v.resolvedAt = str(a.resolvedAt);
            v.note = a.note;
            d.adjustments.add(v);
        }

        for (ClearingReceiptEntity r : ClearingReceiptEntity.<ClearingReceiptEntity>list("caseId", c.id)) {
            ReceiptView v = new ReceiptView();
            v.receiptNo = r.receiptNo;
            v.status = r.status.name();
            v.settledLineCount = r.settledLineCount;
            v.settledAmountCents = r.settledAmountCents;
            v.receivedAt = str(r.receivedAt);
            v.note = r.note;
            d.receipts.add(v);
        }

        for (CaseEventEntity e : CaseEventEntity.<CaseEventEntity>list("caseId", c.id)) {
            EventView v = new EventView();
            v.code = e.code;
            v.detail = e.detail;
            v.refType = e.refType;
            v.refId = e.refId;
            v.at = str(e.at);
            d.events.add(v);
        }
        d.events.sort(Comparator.comparing(v -> v.at));
        return d;
    }

    private CardView card(long id) {
        CardEntity c = CardEntity.findById(id);
        CardView v = new CardView();
        v.id = c.id;
        v.cardNo = c.cardNo;
        v.cardNoMasked = mask(c.cardNo);
        v.holderName = c.holderName;
        v.status = c.status.name();
        v.statusLabel = cardLabel(c.status);
        v.balanceCents = c.balanceCents;
        v.balanceText = money(c.balanceCents);
        v.pendingFareCents = sumPending(c.id, false);
        v.pendingFareCount = countPending(c.id);
        v.issuedAt = str(c.issuedAt);
        v.blockedAt = str(c.blockedAt);
        v.settledLedgerSumCents = sumSettled(c.id);
        return v;
    }

    private List<TransactionEntity> txns(long cardId) {
        var list = TransactionEntity.<TransactionEntity>list("cardId", cardId);
        list.sort(Comparator.comparing((TransactionEntity t) -> t.occurredAt).thenComparing(t -> t.id));
        return list;
    }

    private TxnView txn(TransactionEntity t, String side) {
        TxnView v = new TxnView();
        v.id = t.id;
        v.side = side;
        CardEntity c = CardEntity.findById(t.cardId);
        v.cardNoMasked = mask(c.cardNo);
        v.kind = t.kind.name();
        v.kindLabel = kindLabel(t.kind);
        v.signedAmountCents = t.signedAmountCents;
        v.amountText = (t.signedAmountCents >= 0 ? "+" : "-")
                + money(Math.abs(t.signedAmountCents));
        v.status = t.status.name();
        v.channel = t.channel.name();
        v.occurredAt = str(t.occurredAt);
        v.settledAt = str(t.settledAt);
        v.receiptNo = t.receiptNo;
        v.deviceId = t.deviceId;
        v.eventKey = t.eventKey;
        v.linkedTxnId = t.linkedTxnId;
        v.note = t.note;
        return v;
    }

    private long sumPending(long cardId, boolean ignored) {
        return TransactionEntity.<TransactionEntity>list(
                        "cardId = ?1 and status = ?2 and kind = ?3",
                        cardId, TxnStatus.PENDING, TxnKind.FARE).stream()
                .mapToLong(t -> -t.signedAmountCents).sum();
    }

    private long countPending(long cardId) {
        return TransactionEntity.count("cardId = ?1 and status = ?2 and kind = ?3",
                cardId, TxnStatus.PENDING, TxnKind.FARE);
    }

    private long sumSettled(long cardId) {
        return TransactionEntity.<TransactionEntity>list("cardId", cardId).stream()
                .filter(t -> t.status == TxnStatus.SETTLED)
                .mapToLong(t -> t.signedAmountCents).sum();
    }

    static String mask(String no) {
        if (no == null || no.length() < 8) return "****";
        return no.substring(0, 4) + " **** **** " + no.substring(no.length() - 4);
    }

    private static String str(java.time.Instant i) {
        return i == null ? null : i.toString();
    }

    static String money(long cents) {
        long a = Math.abs(cents);
        return (cents < 0 ? "-" : "") + (a / 100) + "." + String.format("%02d", a % 100);
    }

    private String label(CaseStatus s) {
        return switch (s) {
            case REPORTED -> "已受理";
            case OLD_CARD_BLOCKED -> "旧卡已停用";
            case WAITING_CLEARING -> "等待在途消费清算";
            case READY_TO_TRANSFER -> "可转余额已确定";
            case TRANSFERRED -> "余额已转入新卡";
            case FAILED -> "办理失败（记录保留，可重试）";
        };
    }

    private String cardLabel(CardStatus s) {
        return switch (s) {
            case ACTIVE -> "正常";
            case LOST -> "已挂失停用";
            case REPLACED -> "已补卡·永久停用";
        };
    }

    private String kindLabel(TxnKind k) {
        return switch (k) {
            case FARE -> "乘车消费";
            case RECHARGE -> "充值";
            case TRANSFER_OUT -> "补卡转出";
            case TRANSFER_IN -> "补卡转入";
            case LATE_OFFSET -> "晚到差额挂平";
            case LATE_CLAWBACK -> "晚到消费追扣";
        };
    }

    private String phase(ReplacementCaseEntity c) {
        return switch (c.status) {
            case OLD_CARD_BLOCKED -> "旧卡已停用，尚未制发新卡";
            case WAITING_CLEARING -> "余额仍在等待清算：尚有在途消费未到回执，可转余额暂不最终确定";
            case READY_TO_TRANSFER -> "在途消费已全部清算，可转余额已确定，等待执行转入新卡";
            case TRANSFERRED -> "余额已转入新卡，可正常刷卡使用";
            case FAILED -> "办理失败：处理记录已保存，未完成的转移未丢失，可重新发起";
            case REPORTED -> "已受理";
        };
    }
}
