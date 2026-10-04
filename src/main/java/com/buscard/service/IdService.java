package com.buscard.service;

import jakarta.enterprise.context.ApplicationScoped;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 证件号哈希与脱敏工具。明文证件号不落库。
 */
@ApplicationScoped
public class IdService {

    public String hashIdNumber(String idNumber) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(idNumber.trim().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 卡号脱敏：保留首字符与末两位，如 C1001 -> C****01 */
    public String maskCardId(String cardId) {
        if (cardId == null) {
            return null;
        }
        int len = cardId.length();
        if (len <= 3) {
            return "**";
        }
        return cardId.charAt(0) + "****" + cardId.substring(len - 2);
    }
}
