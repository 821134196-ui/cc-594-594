package com.buscard.service;

import com.buscard.model.Card;
import com.buscard.model.CardTransaction;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * 初始演示数据：一张可操作的正常卡 C1001（张伟），余额 100.00。
 * 证件号明文不入库，仅存哈希。演示证件号：110101199001011234。
 */
@ApplicationScoped
public class SeedData {

    public static final String DEMO_CARD = "C1001";
    public static final String DEMO_ID_NUMBER = "110101199001011234";
    public static final String DEMO_HOLDER = "张伟";

    @Inject
    EntityManager em;

    @Inject
    IdService idService;

    @Transactional
    public void onStart(@Observes StartupEvent ev) {
        Long n = em.createQuery("select count(c) from Card c", Long.class).getSingleResult();
        if (n > 0) {
            return;
        }
        seed();
    }

    @Transactional
    public void seed() {
        Long n = em.createQuery("select count(c) from Card c", Long.class).getSingleResult();
        if (n > 0) {
            return;
        }
        Card c = new Card();
        c.setId(DEMO_CARD);
        c.setHolderName(DEMO_HOLDER);
        c.setIdHash(idService.hashIdNumber(DEMO_ID_NUMBER));
        c.setBalance(new BigDecimal("100.00"));
        c.setStatus("ACTIVE");
        em.persist(c);

        Instant t0 = Instant.now().minus(7, ChronoUnit.DAYS);
        topup(c.getId(), new BigDecimal("100.00"), new BigDecimal("100.00"), t0);
    }

    private void topup(String cardId, BigDecimal amount, BigDecimal balanceAfter, Instant at) {
        CardTransaction t = new CardTransaction();
        t.setId("TXN-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase());
        t.setCardId(cardId);
        t.setType("TOPUP");
        t.setAmount(amount);
        t.setBalanceAfter(balanceAfter);
        t.setStatus("POSTED");
        t.setOccurredAt(at);
        t.setPostedAt(at);
        t.setNote("开卡充值");
        em.persist(t);
    }
}
