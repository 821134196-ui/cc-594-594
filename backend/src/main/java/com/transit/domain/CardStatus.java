package com.transit.domain;

/** 卡片状态 */
public enum CardStatus {
    ACTIVE,       // 正常可用
    LOST,         // 已挂失停用（补卡流程中，在途消费尚未处理完）
    REPLACED      // 终态：余额已转出、旧卡永久停用
}
