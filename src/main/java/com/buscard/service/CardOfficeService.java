package com.buscard.service;

import com.buscard.model.Card;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 制卡服务（本地模拟）。
 * 现实中是独立的制卡中心：写卡面、写芯片卡号，可能设备故障。
 * 本模拟只负责“制出一张新卡对象”，业务持久化与单据状态由 ReplacementService 管理，
 * 这样制卡失败时补卡单与未完成的转移不会被回滚丢失。
 */
@ApplicationScoped
public class CardOfficeService {

    public Card manufacture(String holderName, String idHash, boolean simulateFailure) {
        if (simulateFailure) {
            throw new CardIssueException("制卡中心设备通讯故障（模拟），新卡未写出");
        }
        Card card = new Card();
        card.setId("NC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        card.setHolderName(holderName);
        card.setIdHash(idHash);
        card.setBalance(BigDecimal.ZERO);
        card.setStatus("ACTIVE");
        return card;
    }

    public static class CardIssueException extends RuntimeException {
        public CardIssueException(String message) {
            super(message);
        }
    }
}
