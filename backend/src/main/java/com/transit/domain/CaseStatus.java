package com.transit.domain;

/** 补卡案件状态 */
public enum CaseStatus {
    REPORTED,            // 已受理、身份已核验
    OLD_CARD_BLOCKED,    // 旧卡已停用
    WAITING_CLEARING,    // 已制新卡，仍有在途消费，等待清算
    READY_TO_TRANSFER,   // 在途消费全部清算，可转余额已确定
    TRANSFERRED,         // 余额转移完成
    FAILED               // 补卡失败（处理记录保留，可重新发起制卡）
}
