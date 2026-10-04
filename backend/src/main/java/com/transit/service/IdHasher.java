package com.transit.service;

import jakarta.enterprise.context.ApplicationScoped;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 证件号哈希/核验（演示级，不依赖外部身份服务） */
@ApplicationScoped
public class IdHasher {

    public String hash(String idNo) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(idNo.trim().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public boolean matches(String idNo, String storedHash) {
        return hash(idNo).equalsIgnoreCase(storedHash);
    }

    /** 脱敏：只留前 3 位和后 4 位 */
    public static String maskIdNo(String idNo) {
        if (idNo == null || idNo.length() < 8) return "****";
        return idNo.substring(0, 3) + "***********" + idNo.substring(idNo.length() - 4);
    }
}
