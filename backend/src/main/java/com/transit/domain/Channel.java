package com.transit.domain;

/** 交易来源渠道 */
public enum Channel {
    GATE,      // 在线闸机实时上报
    CLEARING,  // 离线批次清算回执（可能晚到）
    SYSTEM     // 系统内部账（余额转移、追扣）
}
