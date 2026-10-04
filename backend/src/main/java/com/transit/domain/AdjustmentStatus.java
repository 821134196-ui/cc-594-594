package com.transit.domain;

/**
 * 晚到交易差额处理状态。
 * 转移完成后才清算到的旧卡消费：原流水保留，差额挂账追扣，不得改旧交易抹平。
 */
public enum AdjustmentStatus {
    PENDING_CLAWBACK, // 待从新卡追扣（新卡余额不足或已挂失时保持此态，记录不丢）
    RESOLVED          // 已从新卡追回
}
