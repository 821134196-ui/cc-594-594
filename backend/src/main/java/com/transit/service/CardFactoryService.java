package com.transit.service;

import com.transit.entity.CardEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.security.SecureRandom;

/**
 * 本地“制卡服务”模拟：生成不冲突的新卡号、写卡、返回成品卡。
 * armFailure() 用于演示制卡失败（补卡失败也要落记录）。
 */
@ApplicationScoped
public class CardFactoryService {

    private static final String BIN = "6200";
    private final SecureRandom random = new SecureRandom();

    @Inject
    IdHasher idHasher;

    private volatile boolean failNext = false;

    /** 让下一次制卡失败（演示用开关） */
    public void armFailure() {
        this.failNext = true;
    }

    public CardEntity produce(String holderName, String idNo, String producedBy) {
        return produceForHolder(holderName, idHasher.hash(idNo), producedBy);
    }

    /** 补卡：新卡属于同一持卡人，直接沿用旧卡上的身份哈希（哈希不可反解，也无需反解） */
    public CardEntity produceForHolder(String holderName, String idHash, String producedBy) {
        if (failNext) {
            failNext = false;
            throw new CardFactoryException("制卡服务超时：写卡器无响应（模拟故障）");
        }
        CardEntity c = new CardEntity();
        c.cardNo = nextFreeCardNo();
        c.holderName = holderName;
        c.idHash = idHash;
        c.balanceCents = 0;
        c.producedBy = producedBy;
        c.issuedAt = java.time.Instant.now();
        c.persist();
        return c;
    }

    private String nextFreeCardNo() {
        for (int i = 0; i < 20; i++) {
            StringBuilder sb = new StringBuilder(BIN);
            for (int j = 0; j < 12; j++) sb.append(random.nextInt(10));
            String no = sb.toString();
            if (CardEntity.count("cardNo", no) == 0) return no;
        }
        throw new CardFactoryException("制卡服务：卡号分配冲突");
    }

    public static class CardFactoryException extends RuntimeException {
        public CardFactoryException(String m) { super(m); }
    }
}
