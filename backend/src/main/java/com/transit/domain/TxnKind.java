package com.transit.domain;

/** 流水类型 */
public enum TxnKind {
    FARE,            // 乘车消费（扣款，负）
    RECHARGE,        // 充值（入账，正）
    TRANSFER_OUT,    // 补卡：旧卡余额转出（负）
    TRANSFER_IN,     // 补卡：新卡余额转入（正）
    LATE_OFFSET,     // 晚到消费的资金平移：旧卡挂平的补偿分录（正，与晚到 FARE 配对）
    LATE_CLAWBACK    // 晚到消费追扣：新卡实际承担（负，与原 FARE / LATE_OFFSET 配对）
}
