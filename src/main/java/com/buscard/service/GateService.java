package com.buscard.service;

import com.buscard.model.Card;
import com.buscard.model.CardTransaction;
import com.buscard.model.LateDifference;
import com.buscard.model.OfflineSwipeRecord;
import com.buscard.model.Replacement;
import com.buscard.model.ReplacementEvent;
import com.buscard.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 闸机与在途消费模拟。
 *
 * 在线刷卡：实时鉴权，旧卡挂失后立即被拒，不产生任何扣款。
 * 离线刷卡：闸机本地记账（OfflineSwipeRecord），不同步、不扣余额；
 *           同步后成为 PENDING 在途流水；清算时凭回执入账。
 *
 * 幂等全部靠业务唯一键（pendingRef / receiptId）而不是“看看状态”，
 * 重复回调不会重复扣款。
 */
@ApplicationScoped
public class GateService {

    @Inject
    EntityManager em;

    @Inject
    ReplacementService replacementService;

    /* ---------------- 在线刷卡 ---------------- */

    @Transactional
    public GateSwipeResult onlineSwipe(String cardId, BigDecimal amount, String gateId) {
        Card card = mustCard(cardId);
        Instant now = Instant.now();
        if (!"ACTIVE".equals(card.getStatus())) {
            // 停用旧卡刷卡被拒绝：不产生任何扣款流水，只在补卡单上留痕
            Replacement r = replacementService.findReplacementForCard(cardId);
            if (r != null) {
                addEvent(r.getId(), "SWIPE_REJECTED",
                        "闸机 %s 拒绝刷卡：卡片状态 %s（已停用），本次未扣款".formatted(gateId, card.getStatus()),
                        null);
            }
            return new GateSwipeResult(false, "REJECTED_CARD_" + card.getStatus(),
                    card.getStatus(), card.getBalance(), null);
        }
        BigDecimal newBalance = card.getBalance().subtract(amount);
        card.setBalance(newBalance);
        CardTransaction tx = newTx(cardId, "FARE", amount, newBalance, "POSTED", now);
        tx.setPostedAt(now);
        tx.setNote("闸机 %s 在线实时扣款".formatted(gateId));
        em.persist(tx);
        return new GateSwipeResult(true, "ACCEPTED", card.getStatus(), newBalance, tx.getId());
    }

    /* ---------------- 离线刷卡（闸机本地队列） ---------------- */

    @Transactional
    public OfflineSwipeRecord offlineSwipeAt(String cardId, BigDecimal amount, String gateId, Instant occurredAt) {
        mustCard(cardId); // 卡须存在；离线闸机不做停用鉴权，清算时才按发生时间判定
        OfflineSwipeRecord rec = new OfflineSwipeRecord();
        rec.setPendingRef("PEND-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase());
        rec.setCardId(cardId);
        rec.setGateId(gateId);
        rec.setAmount(amount);
        rec.setOccurredAt(occurredAt != null ? occurredAt : Instant.now());
        em.persist(rec);
        return rec;
    }

    /* ---------------- 闸机队列同步：本地记录 -> 中心 PENDING 流水 ---------------- */

    @Transactional
    public int syncGate(String gateId) {
        var q = em.createQuery(
                        "select r from OfflineSwipeRecord r where r.synced = false " +
                                (gateId != null ? "and r.gateId = :g " : "") + "order by r.occurredAt",
                        OfflineSwipeRecord.class);
        if (gateId != null) {
            q.setParameter("g", gateId);
        }
        List<OfflineSwipeRecord> target = q.getResultList();
        int n = 0;
        for (OfflineSwipeRecord r : target) {
            // pendingRef 即交易号，重复同步靠唯一键天然挡住
            CardTransaction tx = newTx(r.getCardId(), "FARE", r.getAmount(), null, "PENDING", r.getOccurredAt());
            tx.setId(r.getPendingRef());
            tx.setPendingRef(r.getPendingRef());
            tx.setPostedAt(null); // 尚未入账
            tx.setNote("闸机 %s 离线乘车，已同步、等待清算".formatted(r.getGateId()));
            em.persist(tx);
            r.setSynced(true);
            r.setSyncedAt(Instant.now());
            n++;
        }
        return n;
    }

    /* ---------------- 凭回执清算在途消费 ---------------- */

    @Transactional
    public ClearResult clear(String pendingRef, String receiptId) {
        OfflineSwipeRecord rec = em.find(OfflineSwipeRecord.class, pendingRef);
        if (rec == null) {
            throw ApiException.notFound("找不到离线刷卡记录 " + pendingRef);
        }
        // 幂等：同一回执（或该记录）重复送达 → 返回既成事实，绝不再扣一次钱
        if (rec.isCleared() || findTxByReceipt(receiptId) != null) {
            Replacement r = replacementService.findReplacementForCard(rec.getCardId());
            if (r != null) {
                addEvent(r.getId(), "DUPLICATE_RECEIPT",
                        "收到重复清算回执 %s（对应 %s），系统幂等忽略，未重复扣款"
                                .formatted(receiptId, pendingRef), receiptId);
            }
            CardTransaction tx = findTxByPendingRef(pendingRef);
            return new ClearResult(false, true, "DUPLICATE_RECEIPT_IGNORED",
                    tx != null ? tx.getId() : null, receiptId);
        }
        CardTransaction tx = findTxByPendingRef(pendingRef);
        if (tx == null) {
            throw ApiException.badRequest("该离线记录尚未同步，无法清算：" + pendingRef);
        }

        Card card = mustCard(rec.getCardId());
        Instant now = Instant.now();
        Replacement repl = replacementService.findReplacementForCard(rec.getCardId());
        Instant reportedAt = repl != null ? repl.getReportedAt() : null;
        boolean reported = reportedAt != null;
        boolean postLossRide = reported && rec.getOccurredAt().isAfter(reportedAt);

        if (postLossRide) {
            // 挂失停用之后的离线乘车：拒绝追扣。原始流水保留为 REJECTED，不抹账
            tx.setStatus("REJECTED");
            tx.setReceiptId(receiptId);
            tx.setPostedAt(now);
            tx.setNote("乘车发生于挂失停用之后，清算拒绝追扣，回执 " + receiptId);
            rec.setCleared(true);
            rec.setClearedAt(now);
            rec.setReceiptId(receiptId);
            addEvent(repl.getId(), "POST_LOSS_ATTEMPT",
                    "挂失后离线乘车被拒追扣：%s %s 元（发生于挂失后），回执 %s"
                            .formatted(pendingRef, rec.getAmount(), receiptId), receiptId);
            replacementService.afterPendingCleared(repl.getId());
            return new ClearResult(true, false, "REJECTED_POST_LOSS", tx.getId(), receiptId);
        }

        // 晚到判定：挂失时系统是否已知道这笔在途（看同步时间）。
        // 挂失前已同步 = 挂失时已在途；挂失后才同步 = 晚到消费。
        boolean knownAtReport = reported && rec.getSyncedAt() != null
                && !rec.getSyncedAt().isAfter(reportedAt);
        boolean late = reported && !knownAtReport;

        // 入账：保留原始流水，仅 PENDING -> POSTED（不改金额、不改发生时间）
        BigDecimal newBalance = card.getBalance().subtract(rec.getAmount());
        card.setBalance(newBalance);
        tx.setStatus("POSTED");
        tx.setReceiptId(receiptId);
        tx.setPostedAt(now);
        tx.setBalanceAfter(newBalance);
        tx.setLate(late);
        tx.setNote(late
                ? "晚到消费：挂失后才同步/清算，但乘车发生在挂失前，原始流水保留，回执 " + receiptId
                : "在途消费清算入账，回执 " + receiptId);
        rec.setCleared(true);
        rec.setClearedAt(now);
        rec.setReceiptId(receiptId);

        if (repl != null) {
            if ("TRANSFERRED".equals(repl.getStatus())) {
                // 钱已转走后才晚到：旧卡如实变负，独立登记未解决差额，沿卡链追账
                replacementService.registerAfterTransferLate(repl, tx, rec.getAmount(), now);
            } else if (late) {
                repl.setLateTotal(repl.getLateTotal().add(rec.getAmount()));
                replacementService.recomputeTransferable(repl);
                LateDifference diff = new LateDifference();
                diff.setId("DIFF-" + UUID.randomUUID().toString().substring(0, 10).toUpperCase());
                diff.setReplacementId(repl.getId());
                diff.setCardId(card.getId());
                diff.setTxnId(tx.getId());
                diff.setAmount(rec.getAmount());
                diff.setOccurredAt(rec.getOccurredAt());
                diff.setRecordedAt(now);
                diff.setStage("BEFORE_TRANSFER");
                diff.setResolved(true);
                diff.setResolution("转移前计入可转余额冲减（原始流水保留，未修改旧交易）");
                diff.setResolvedAt(now);
                em.persist(diff);
                addEvent(repl.getId(), "LATE_FARE",
                        "晚到消费 %s 元入账（%s，乘车在挂失前、挂失后才清算），可转余额等额减少，差额单 %s"
                                .formatted(rec.getAmount(), pendingRef, diff.getId()), tx.getId());
                replacementService.afterPendingCleared(repl.getId());
            } else {
                repl.setPendingSettledTotal(repl.getPendingSettledTotal().add(rec.getAmount()));
                replacementService.recomputeTransferable(repl);
                addEvent(repl.getId(), "PENDING_SETTLED",
                        "在途消费清算 %s 元（%s，回执 %s），可转余额等额减少"
                                .formatted(rec.getAmount(), pendingRef, receiptId), receiptId);
                replacementService.afterPendingCleared(repl.getId());
            }
        }
        return new ClearResult(true, false, late ? "POSTED_LATE" : "POSTED", tx.getId(), receiptId);
    }

    /* ---------------- 查询 ---------------- */

    /** 沿卡链在当前有效卡上追收晚到差额（转移后晚到场景） */
    public void recoverFromHead(LateDifference diff, Card head, Replacement headRepl, Instant now) {
        BigDecimal before = head.getBalance();
        BigDecimal nb = before.subtract(diff.getAmount());
        head.setBalance(nb);
        CardTransaction fee = newTx(head.getId(), "LATE_RECOVERY", diff.getAmount(),
                nb, "POSTED", now);
        fee.setPostedAt(now);
        fee.setNote("沿卡链追收挂失前晚到车费，差额单 " + diff.getId() + "，原始交易 " + diff.getTxnId());
        em.persist(fee);
        diff.setResolved(true);
        diff.setStage("RECOVERED");
        diff.setResolution("已从卡链有效卡 %s 追收（流水 %s）".formatted(head.getId(), fee.getId()));
        diff.setResolvedAt(now);
        if (headRepl != null) {
            replacementService.recomputeTransferable(headRepl);
        }
        addEvent(diff.getReplacementId(), "LATE_RECOVERED",
                "差额单 %s 已沿卡链从 %s 追收 %s 元（该卡余额 %s -> %s，流水 %s）".formatted(
                        diff.getId(), head.getId(), diff.getAmount(), before, nb, fee.getId()),
                fee.getId());
    }

    public long countPending(String cardId) {
        return em.createQuery("select count(t) from CardTransaction t where t.cardId = :c and t.status = 'PENDING'",
                        Long.class).setParameter("c", cardId).getSingleResult();
    }

    private Card mustCard(String cardId) {
        Card c = em.find(Card.class, cardId);
        if (c == null) {
            throw ApiException.notFound("卡片不存在：" + cardId);
        }
        return c;
    }

    private CardTransaction findTxByPendingRef(String ref) {
        var rows = em.createQuery("select t from CardTransaction t where t.pendingRef = :p",
                        CardTransaction.class).setParameter("p", ref).setMaxResults(1).getResultList();
        return rows.isEmpty() ? null : rows.get(0);
    }

    private CardTransaction findTxByReceipt(String receipt) {
        if (receipt == null) {
            return null;
        }
        var rows = em.createQuery("select t from CardTransaction t where t.receiptId = :r",
                        CardTransaction.class).setParameter("r", receipt).setMaxResults(1).getResultList();
        return rows.isEmpty() ? null : rows.get(0);
    }

    private CardTransaction newTx(String cardId, String type, BigDecimal amount,
                                  BigDecimal balanceAfter, String status, Instant occurredAt) {
        CardTransaction tx = new CardTransaction();
        tx.setId("TXN-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase());
        tx.setCardId(cardId);
        tx.setType(type);
        tx.setAmount(amount);
        tx.setBalanceAfter(balanceAfter);
        tx.setStatus(status);
        tx.setOccurredAt(occurredAt);
        return tx;
    }

    public ReplacementEvent addEvent(String replacementId, String type, String message, String refId) {
        ReplacementEvent e = new ReplacementEvent(
                "EVT-" + UUID.randomUUID().toString().substring(0, 10).toUpperCase(),
                replacementId, Instant.now(), type, message, refId);
        em.persist(e);
        return e;
    }

    public record GateSwipeResult(boolean accepted, String reason, String cardStatus,
                                  BigDecimal balance, String txnId) {}

    public record ClearResult(boolean applied, boolean duplicate, String outcome,
                              String txnId, String receiptId) {}
}
