package com.buscard.service;

import com.buscard.model.Card;
import com.buscard.model.CardTransaction;
import com.buscard.model.LateDifference;
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
 * 补卡办理核心：
 *  1) 客服核身 -> 旧卡立即停用（先确认停用）
 *  2) 制卡中心制新卡（失败保留单据，可重试，不丢未完成转移）
 *  3) 等待在途消费逐笔清算（晚到消费只登记差额、冲减可转余额，不改旧流水）
 *  4) 在途清零后一次性转移：旧卡转出金额 == 新卡转入金额（同一变量、同一事务、回执幂等）
 *  5) 新卡再次挂失：关闭旧单、另开新单，沿卡链继续
 */
@ApplicationScoped
public class ReplacementService {

    @Inject
    EntityManager em;

    @Inject
    IdService idService;

    @Inject
    CardOfficeService cardOffice;

    @Inject
    GateService gateService;

    /* ---------------- 1. 核身挂失 ---------------- */

    @Transactional
    public Replacement reportLost(String cardId, String idNumber) {
        Card card = em.find(Card.class, cardId);
        if (card == null) {
            throw ApiException.notFound("卡片不存在：" + cardId);
        }
        if (!card.getIdHash().equals(idService.hashIdNumber(idNumber))) {
            throw new ApiException(403, "身份核验失败：证件号与持卡人不匹配");
        }
        Replacement existing = findByOldCard(cardId);
        if (existing != null) {
            throw ApiException.conflict("该卡已有补卡单 %s（状态 %s），一张卡只能关联一次补卡"
                    .formatted(existing.getId(), existing.getStatus()));
        }
        if ("REPLACED".equals(card.getStatus())) {
            throw ApiException.conflict("旧卡已完成余额转出，不能重复补卡");
        }
        Instant now = Instant.now();
        card.setStatus("STOLEN");
        card.setReportedAt(now);

        Replacement r = new Replacement();
        r.setId("RPL-" + UUID.randomUUID().toString().substring(0, 10).toUpperCase());
        r.setOldCardId(cardId);
        r.setHolderName(card.getHolderName());
        r.setStatus("REPORTED");
        r.setReportedBalance(card.getBalance());
        r.setTransferableAmount(card.getBalance());
        r.setReportedAt(now);
        em.persist(r);

        long pending = countPending(cardId);
        gateService.addEvent(r.getId(), "REPORTED",
                "客服核身通过，旧卡 %s 已停用；挂失瞬间余额 %s 元，在途待清算 %d 笔"
                        .formatted(cardId, card.getBalance(), pending), null);
        return r;
    }

    /* ---------------- 2. 制新卡（可失败、可重试） ---------------- */

    @Transactional
    public Replacement issueNewCard(String replacementId, boolean simulateFailure) {
        Replacement r = mustReplacement(replacementId);
        if ("TRANSFERRED".equals(r.getStatus()) || "RE_REPORTED".equals(r.getStatus())) {
            throw ApiException.conflict("补卡单 %s 已结案（%s），不能再制卡".formatted(replacementId, r.getStatus()));
        }
        r.setAttemptCount(r.getAttemptCount() + 1);
        try {
            Card newCard = cardOffice.manufacture(r.getHolderName(),
                    em.find(Card.class, r.getOldCardId()).getIdHash(), simulateFailure);
            em.persist(newCard);
            r.setNewCardId(newCard.getId());
            r.setStatus("ISSUED");
            r.setFailReason(null);
            r.setIssuedAt(Instant.now());
            gateService.addEvent(r.getId(), "ISSUED",
                    "新卡 %s 制出，与旧卡 %s 一对一关联；等待在途消费清算完成后转移余额"
                            .formatted(newCard.getId(), r.getOldCardId()), newCard.getId());
        } catch (CardOfficeService.CardIssueException e) {
            // 关键：制卡失败也提交，单据与可转余额完整保留，客服可重试
            r.setStatus("FAILED");
            r.setFailReason(e.getMessage());
            gateService.addEvent(r.getId(), "ISSUE_FAILED",
                    "第 %d 次制卡失败：%s。补卡单保留，未完成的余额转移不受影响，可重试。"
                            .formatted(r.getAttemptCount(), e.getMessage()), null);
        }
        // 若制卡成功且在途恰已清零，立即转移
        if ("ISSUED".equals(r.getStatus())) {
            tryAutoTransfer(r);
        }
        return r;
    }

    /* ---------------- 3/4. 转移（自动与手动同一入口） ---------------- */

    /** 在途清算回调后尝试自动转移 */
    public void afterPendingCleared(String replacementId) {
        Replacement r = em.find(Replacement.class, replacementId);
        if (r != null && "ISSUED".equals(r.getStatus())) {
            tryAutoTransfer(r);
        }
    }

    private void tryAutoTransfer(Replacement r) {
        if (countPending(r.getOldCardId()) == 0) {
            doTransfer(r, "TR-" + r.getId());
        }
    }

    @Transactional
    public Replacement manualTransfer(String replacementId, String receiptId) {
        Replacement r = mustReplacement(replacementId);
        if ("TRANSFERRED".equals(r.getStatus())) {
            // 重复转移请求幂等返回，不多转一分钱
            gateService.addEvent(r.getId(), "DUPLICATE_TRANSFER",
                    "收到重复的余额转移请求（回执 %s），单据已转移 %s 元，幂等忽略"
                            .formatted(receiptId, r.getTransferReceiptId()), r.getTransferReceiptId());
            return r;
        }
        if (r.getNewCardId() == null || "FAILED".equals(r.getStatus()) || "REPORTED".equals(r.getStatus())) {
            throw ApiException.conflict("新卡尚未制出（当前状态 %s），无法转移".formatted(r.getStatus()));
        }
        long pending = countPending(r.getOldCardId());
        if (pending > 0) {
            throw ApiException.conflict("仍有 %d 笔在途消费未清算，余额保留等待，不能转移".formatted(pending));
        }
        doTransfer(r, receiptId != null ? receiptId : "TR-" + r.getId());
        return r;
    }

    /** 唯一真正动钱的地方：旧卡转出与新卡转入用同一个 amount，在同一事务内完成 */
    private void doTransfer(Replacement r, String receiptId) {
        if (r.getTransferReceiptId() != null || "TRANSFERRED".equals(r.getStatus())) {
            return; // 幂等保险
        }
        Instant now = Instant.now();
        Card oldCard = em.find(Card.class, r.getOldCardId());
        Card newCard = em.find(Card.class, r.getNewCardId());

        // 转移前先处理沿卡链追到这张旧卡的晚到差额
        recoverChainDifferences(r, now);

        BigDecimal amount = oldCard.getBalance().max(BigDecimal.ZERO);

        CardTransaction out = txn(oldCard.getId(), "TRANSFER_OUT", amount);
        oldCard.setBalance(oldCard.getBalance().subtract(amount)); // 正常恒为 0
        out.setBalanceAfter(oldCard.getBalance());
        out.setPostedAt(now);
        out.setNote("补卡 %s 余额转出，转移回执 %s（挂失快照 %s - 在途清算 %s - 晚到 %s）"
                .formatted(r.getId(), receiptId, r.getReportedBalance(),
                        r.getPendingSettledTotal(), r.getLateTotal()));
        em.persist(out);
        oldCard.setStatus("REPLACED");

        CardTransaction in = txn(newCard.getId(), "TRANSFER_IN", amount);
        newCard.setBalance(newCard.getBalance().add(amount)); // 与转出严格同一 amount
        in.setBalanceAfter(newCard.getBalance());
        in.setPostedAt(now);
        in.setReceiptId(receiptId);
        in.setNote("补卡 %s 余额转入，转移回执 %s，对应旧卡转出流水 %s"
                .formatted(r.getId(), receiptId, out.getId()));
        // 两条流水共用同一笔转移业务，但 receiptId 列全局唯一：转出侧用 -OUT 后缀，
        // 转移回执本体保留在 replacement.transferReceiptId 与转入流水上，仍可双向核对。
        out.setReceiptId(receiptId + "-OUT");
        em.persist(in);

        r.setTransferredAmount(amount);
        r.setTransferableAmount(amount);
        r.setStatus("TRANSFERRED");
        r.setTransferredAt(now);
        r.setTransferReceiptId(receiptId);

        gateService.addEvent(r.getId(), "TRANSFERRED",
                "在途消费已全部清算，余额 %s 元已从旧卡 %s 转入新卡 %s（转出流水 %s / 转入流水 %s，金额一致，回执 %s）"
                        .formatted(amount, oldCard.getId(), newCard.getId(), out.getId(), in.getId(), receiptId),
                receiptId);
    }

    /* ---------------- 5. 新卡再次挂失 ---------------- */

    @Transactional
    public Replacement reReportLost(String cardId, String idNumber) {
        Replacement r = reportLost(cardId, idNumber);
        // 找到以这张卡为“新卡”的上一单并关闭，卡链不断
        Replacement prev = em.createQuery(
                        "select r from Replacement r where r.newCardId = :c order by r.reportedAt desc",
                        Replacement.class).setParameter("c", cardId).setMaxResults(1).getResultList()
                .stream().findFirst().orElse(null);
        if (prev != null && !"RE_REPORTED".equals(prev.getStatus())) {
            prev.setStatus("RE_REPORTED");
            prev.setClosedAt(Instant.now());
            gateService.addEvent(prev.getId(), "RE_REPORTED",
                    "新卡 %s 再次挂失，本单关闭；余额与差额沿新补卡单 %s 继续处理，历史记录全部保留"
                            .formatted(cardId, r.getId()), r.getId());
        }
        return r;
    }

    /* ---------------- 晚到差额（转移后） ---------------- */

    /** 旧单已转移后晚到：旧卡如实变负（调用方已扣款），登记差额并沿卡链追收 */
    public void registerAfterTransferLate(Replacement origin, CardTransaction lateTx,
                                          BigDecimal amount, Instant now) {
        LateDifference diff = new LateDifference();
        diff.setId("DIFF-" + UUID.randomUUID().toString().substring(0, 10).toUpperCase());
        diff.setReplacementId(origin.getId());
        diff.setCardId(origin.getOldCardId());
        diff.setTxnId(lateTx.getId());
        diff.setAmount(amount);
        diff.setOccurredAt(lateTx.getOccurredAt());
        diff.setRecordedAt(now);
        diff.setStage("AFTER_TRANSFER");
        em.persist(diff);
        gateService.addEvent(origin.getId(), "LATE_FARE",
                "晚到消费 %s 元在余额转移后才入账，原始流水保留，旧卡余额如实为负；差额单 %s 待沿卡链追收"
                        .formatted(amount, diff.getId()), lateTx.getId());

        Card head = walkToHead(origin);
        Replacement headRepl = findByOldCard(head.getId());
        if (head != null && "ACTIVE".equals(head.getStatus()) && headRepl == null) {
            gateService.recoverFromHead(diff, head, null, now);
        } else {
            gateService.addEvent(origin.getId(), "LATE_FARE_PENDING",
                    "卡链头 %s 暂不可追收（状态 %s），差额单 %s 保留，将在其补卡转移时继续追账"
                            .formatted(head.getId(), head.getStatus(), diff.getId()), diff.getId());
        }
    }

    /** 转移前，把沿卡链追到本卡的未解决晚到差额从本卡追收（冲减本次可转余额） */
    private void recoverChainDifferences(Replacement r, Instant now) {
        List<LateDifference> open = em.createQuery(
                        "select d from LateDifference d where d.resolved = false", LateDifference.class)
                .getResultList();
        for (LateDifference d : open) {
            Replacement origin = em.find(Replacement.class, d.getReplacementId());
            Card head = walkToHead(origin);
            if (head.getId().equals(r.getOldCardId())) {
                // 差额在此卡转移前追收：旧卡余额减少，转出金额自然减少，账目依旧守恒
                BigDecimal nb = head.getBalance().subtract(d.getAmount());
                head.setBalance(nb);
                CardTransaction rec = txn(head.getId(), "LATE_RECOVERY", d.getAmount());
                rec.setBalanceAfter(nb);
                rec.setPostedAt(now);
                rec.setNote("沿卡链追收转移后晚到车费，差额单 " + d.getId() + "，原始交易 " + d.getTxnId());
                em.persist(rec);
                d.setResolved(true);
                d.setStage("RECOVERED");
                d.setResolution("在补卡单 %s 转移前从 %s 追收（流水 %s）"
                        .formatted(r.getId(), head.getId(), rec.getId()));
                d.setResolvedAt(now);
                gateService.addEvent(d.getReplacementId(), "LATE_RECOVERED",
                        "差额单 %s 沿卡链在 %s 转移前追收 %s 元（流水 %s），本次转出等额减少"
                                .formatted(d.getId(), head.getId(), d.getAmount(), rec.getId()), rec.getId());
            }
        }
        recomputeTransferable(r);
    }

    /**
     * 沿卡链找“当前应承担追账的卡”：只穿过已转移（TRANSFERRED）的补卡单。
     * 若下一环节的卡正处于未结案补卡（已挂失/制卡中），链在该卡终止，
     * 它转移时由 recoverChainDifferences 从其旧卡余额中追收。
     */
    private Card walkToHead(Replacement origin) {
        String cardId = origin.getNewCardId();
        while (true) {
            Replacement next = findByOldCard(cardId);
            if (next == null || !"TRANSFERRED".equals(next.getStatus()) || next.getNewCardId() == null) {
                return em.find(Card.class, cardId);
            }
            cardId = next.getNewCardId();
        }
    }

    /* ---------------- 工具 ---------------- */

    public void recomputeTransferable(Replacement r) {
        BigDecimal v = r.getReportedBalance()
                .subtract(r.getPendingSettledTotal())
                .subtract(r.getLateTotal())
                .max(BigDecimal.ZERO);
        r.setTransferableAmount(v);
    }

    public long countPending(String cardId) {
        return em.createQuery("select count(t) from CardTransaction t where t.cardId = :c and t.status = 'PENDING'",
                        Long.class).setParameter("c", cardId).getSingleResult();
    }

    public Replacement findByOldCard(String cardId) {
        return em.createQuery("select r from Replacement r where r.oldCardId = :c", Replacement.class)
                .setParameter("c", cardId).setMaxResults(1).getResultList()
                .stream().findFirst().orElse(null);
    }

    public Replacement findReplacementForCard(String cardId) {
        return em.createQuery(
                        "select r from Replacement r where r.oldCardId = :c or r.newCardId = :c " +
                                "order by r.reportedAt desc", Replacement.class)
                .setParameter("c", cardId).setMaxResults(1).getResultList()
                .stream().findFirst().orElse(null);
    }

    private Replacement mustReplacement(String id) {
        Replacement r = em.find(Replacement.class, id);
        if (r == null) {
            throw ApiException.notFound("补卡单不存在：" + id);
        }
        return r;
    }

    private CardTransaction txn(String cardId, String type, BigDecimal amount) {
        CardTransaction t = new CardTransaction();
        t.setId("TXN-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase());
        t.setCardId(cardId);
        t.setType(type);
        t.setAmount(amount);
        t.setStatus("POSTED");
        t.setOccurredAt(Instant.now());
        return t;
    }
}
