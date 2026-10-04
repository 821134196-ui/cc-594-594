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

import java.time.Instant;
import java.util.List;

/**
 * 只读查询：乘客脱敏进度视图与客服全量追踪视图。
 */
@ApplicationScoped
public class QueryService {

    @Inject
    EntityManager em;

    @Inject
    IdService idService;

    /* ---------------- 乘客：脱敏 + 进度说明 ---------------- */

    public PassengerCase passengerCase(String replacementId) {
        Replacement r = em.find(Replacement.class, replacementId);
        if (r == null) {
            throw ApiException.notFound("补卡单不存在：" + replacementId);
        }
        Card oldCard = em.find(Card.class, r.getOldCardId());
        Card newCard = r.getNewCardId() == null ? null : em.find(Card.class, r.getNewCardId());

        long pending = em.createQuery(
                        "select count(t) from CardTransaction t where t.cardId = :c and t.status = 'PENDING'", Long.class)
                .setParameter("c", r.getOldCardId()).getSingleResult();
        boolean waiting = pending > 0 && !"TRANSFERRED".equals(r.getStatus());

        String phase;
        String progressText;
        if ("TRANSFERRED".equals(r.getStatus())) {
            phase = "DONE";
            progressText = "余额已转入新卡 %s，金额 %s 元，可直接使用新卡。"
                    .formatted(mask(newCard.getId()), r.getTransferredAmount());
        } else if ("FAILED".equals(r.getStatus())) {
            phase = "ISSUE_FAILED";
            progressText = "制卡失败，客服正在处理，您的余额不会丢失。";
        } else if (waiting) {
            phase = "WAITING_CLEARING";
            progressText = "旧卡已停用，仍有 %d 笔在途消费等待清算，可转余额暂时保留，清算完成后自动转入新卡。"
                    .formatted(pending);
        } else if (r.getNewCardId() == null) {
            phase = "REPORTED";
            progressText = "挂失已受理，旧卡已停用，等待客服制出新卡。";
        } else {
            phase = "READY";
            progressText = "在途消费已清算完毕，余额即将转入新卡。";
        }

        List<TxnView> oldTx = txViews(r.getOldCardId(), true);
        List<TxnView> newTx = newCard == null ? List.of() : txViews(newCard.getId(), true);
        List<EventView> events = events(r.getId(), true, r.getOldCardId(), r.getNewCardId());
        List<DiffView> diffs = diffs(r.getId(), true);

        return new PassengerCase(r.getId(), mask(r.getOldCardId()), r.getHolderName(),
                r.getStatus(), zhStatus(r.getStatus()), phase, progressText, waiting, pending,
                money(r.getReportedBalance()), money(r.getPendingSettledTotal()), money(r.getLateTotal()),
                money(r.getTransferableAmount()), money(r.getTransferredAmount()),
                r.getTransferReceiptId(), r.getReportedAt(), r.getTransferredAt(),
                cardView(oldCard, true), newCard == null ? null : cardView(newCard, true),
                oldTx, newTx, events, diffs);
    }

    /* ---------------- 客服：全量、可逐笔追溯 ---------------- */

    public AgentCase agentCase(String replacementId) {
        Replacement r = em.find(Replacement.class, replacementId);
        if (r == null) {
            throw ApiException.notFound("补卡单不存在：" + replacementId);
        }
        Card oldCard = em.find(Card.class, r.getOldCardId());
        Card newCard = r.getNewCardId() == null ? null : em.find(Card.class, r.getNewCardId());
        return new AgentCase(
                r, cardView(oldCard, false), newCard == null ? null : cardView(newCard, false),
                txViews(r.getOldCardId(), false),
                newCard == null ? List.of() : txViews(newCard.getId(), false),
                events(r.getId(), false), diffs(r.getId(), false),
                pendingCount(r.getOldCardId()));
    }

    public List<Replacement> listCases() {
        return em.createQuery("select r from Replacement r order by r.reportedAt desc", Replacement.class)
                .getResultList();
    }

    public List<OfflineSwipeRecord> listOffline(String gateId) {
        var q = em.createQuery("select o from OfflineSwipeRecord o " +
                (gateId != null ? "where o.gateId = :g " : "") + "order by o.occurredAt", OfflineSwipeRecord.class);
        if (gateId != null) {
            q.setParameter("g", gateId);
        }
        return q.getResultList();
    }

    public List<Card> listCards() {
        return em.createQuery("select c from Card c order by c.id", Card.class).getResultList();
    }

    /* ---------------- 组装 ---------------- */

    private List<TxnView> txViews(String cardId, boolean masked) {
        return em.createQuery("select t from CardTransaction t where t.cardId = :c order by t.occurredAt, t.id",
                        CardTransaction.class).setParameter("c", cardId).getResultList()
                .stream().map(t -> new TxnView(t.getId(), masked ? mask(t.getCardId()) : t.getCardId(),
                        t.getType(), zhType(t.getType()),
                        money(t.getAmount()), t.getBalanceAfter() == null ? null : money(t.getBalanceAfter()),
                        t.getStatus(), zhTxStatus(t.getStatus()), t.isLate(),
                        t.getOccurredAt(), t.getPostedAt(), t.getReceiptId(), t.getPendingRef(), t.getNote()))
                .toList();
    }

    private List<EventView> events(String replacementId, boolean masked) {
        return events(replacementId, masked, null, null);
    }

    private List<EventView> events(String replacementId, boolean masked, String oldCardId, String newCardId) {
        // 卡链事件可能提到任意卡，乘客视图对全部卡号做掩码
        List<String> allCardIds = masked
                ? em.createQuery("select c.id from Card c", String.class).getResultList()
                : List.of();
        return em.createQuery("select e from ReplacementEvent e where e.replacementId = :r order by e.at",
                        ReplacementEvent.class).setParameter("r", replacementId).getResultList()
                .stream().map(e -> {
                    String msg = e.getMessage();
                    if (masked) {
                        for (String id : allCardIds) {
                            msg = msg.replace(id, mask(id));
                        }
                    }
                    return new EventView(e.getId(), e.getType(), msg,
                            masked ? null : e.getRefId(), e.getAt());
                })
                .toList();
    }

    private List<DiffView> diffs(String replacementId, boolean masked) {
        return em.createQuery("select d from LateDifference d where d.replacementId = :r order by d.recordedAt",
                        LateDifference.class).setParameter("r", replacementId).getResultList()
                .stream().map(d -> new DiffView(d.getId(), masked ? mask(d.getCardId()) : d.getCardId(),
                        d.getTxnId(), money(d.getAmount()),
                        d.getStage(), d.isResolved(), d.getResolution(), d.getOccurredAt(), d.getRecordedAt()))
                .toList();
    }

    private CardView cardView(Card c, boolean masked) {
        return new CardView(masked ? mask(c.getId()) : c.getId(), c.getHolderName(),
                c.getStatus(), zhCardStatus(c.getStatus()), money(c.getBalance()), c.getReportedAt(), c.getVersion());
    }

    private String mask(String s) {
        return idService.maskCardId(s);
    }

    private long pendingCount(String cardId) {
        return em.createQuery("select count(t) from CardTransaction t where t.cardId = :c and t.status = 'PENDING'",
                        Long.class).setParameter("c", cardId).getSingleResult();
    }

    private static String money(java.math.BigDecimal v) {
        return v == null ? null : v.setScale(2).toPlainString();
    }

    static String zhCardStatus(String s) {
        return switch (s) {
            case "ACTIVE" -> "正常";
            case "STOLEN" -> "已挂失停用";
            case "REPLACED" -> "已转出作废";
            case "BLOCKED" -> "停用";
            default -> s;
        };
    }

    static String zhStatus(String s) {
        return switch (s) {
            case "REPORTED" -> "已挂失，等待清算";
            case "ISSUED" -> "新卡已制，等待转移";
            case "TRANSFERRED" -> "余额已转移";
            case "FAILED" -> "制卡失败，待重试";
            case "RE_REPORTED" -> "新卡再挂失，已关闭";
            default -> s;
        };
    }

    static String zhType(String s) {
        return switch (s) {
            case "TOPUP" -> "充值";
            case "FARE" -> "乘车扣款";
            case "TRANSFER_OUT" -> "补卡转出";
            case "TRANSFER_IN" -> "补卡转入";
            case "LATE_RECOVERY" -> "晚到追账";
            default -> s;
        };
    }

    static String zhTxStatus(String s) {
        return switch (s) {
            case "POSTED" -> "已入账";
            case "PENDING" -> "在途待清算";
            case "REJECTED" -> "拒付（挂失后乘车）";
            default -> s;
        };
    }

    /* ---------------- DTO ---------------- */

    public record CardView(String id, String holderName, String status, String statusZh,
                           String balance, Instant reportedAt, long version) {}

    public record TxnView(String id, String cardId, String type, String typeZh, String amount,
                          String balanceAfter, String status, String statusZh, boolean late,
                          Instant occurredAt, Instant postedAt, String receiptId,
                          String pendingRef, String note) {}

    public record EventView(String id, String type, String message, String refId, Instant at) {}

    public record DiffView(String id, String cardId, String txnId, String amount, String stage,
                           boolean resolved, String resolution, Instant occurredAt, Instant recordedAt) {}

    public record PassengerCase(String id, String oldCardId, String holderName, String status, String statusZh,
                                String phase, String progressText, boolean waitingClearing, long pendingCount,
                                String reportedBalance, String pendingSettledTotal, String lateTotal,
                                String transferableAmount, String transferredAmount, String transferReceiptId,
                                Instant reportedAt, Instant transferredAt,
                                CardView oldCard, CardView newCard,
                                List<TxnView> oldTransactions, List<TxnView> newTransactions,
                                List<EventView> events, List<DiffView> differences) {}

    public record AgentCase(Replacement replacement, CardView oldCard, CardView newCard,
                            List<TxnView> oldTransactions, List<TxnView> newTransactions,
                            List<EventView> events, List<DiffView> differences, long pendingCount) {}
}
