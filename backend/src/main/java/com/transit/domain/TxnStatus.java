package com.transit.domain;

/** 流水清算状态 */
public enum TxnStatus {
    PENDING,   // 在途：闸机已记录，清算回执未到，余额尚未实际扣减
    SETTLED    // 已清算入账（金额永不修改，只做状态推进）
}
