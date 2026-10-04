package com.transit.domain;

/** 清算回执处理结果 */
public enum ReceiptStatus {
    ACCEPTED,   // 首次受理
    DUPLICATE   // 重复回执：只登记，绝不重复扣款/重复转移
}
