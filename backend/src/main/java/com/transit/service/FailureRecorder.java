package com.transit.service;

import com.transit.domain.CaseStatus;
import com.transit.domain.TransferStatus;
import com.transit.entity.BalanceTransferEntity;
import com.transit.entity.ReplacementCaseEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;

/**
 * 失败留痕辅助（与外层业务同一事务提交）。
 * 设计约束：SQLite 单连接串行化，不能用 REQUIRES_NEW 嵌套事务；
 * 因此制卡/转账失败时不抛异常回滚，而是写好 FAILED 记录与事件后正常提交，
 * 由调用方把失败标记返回给 REST 层（保证处理记录绝不丢失）。
 */
@ApplicationScoped
public class FailureRecorder {

    @Inject CaseEventRecorder events;

    public BalanceTransferEntity recordTransferFailure(ReplacementCaseEntity c, int attempt,
                                                       long amountCents, String note) {
        BalanceTransferEntity failed = new BalanceTransferEntity();
        failed.caseId = c.id;
        failed.attemptNo = attempt;
        failed.status = TransferStatus.FAILED;
        failed.amountCents = amountCents;
        failed.attemptedAt = Instant.now();
        failed.note = note;
        failed.persist();

        c.status = CaseStatus.FAILED;
        c.failureReason = note;

        events.event(c, "TRANSFER_FAILED",
                note + "；拟转 " + ReplacementService.money(amountCents)
                        + " 元，失败尝试 #" + attempt + " 已留痕，资金未动，可重试",
                "TRANSFER", failed.id);
        return failed;
    }

    public void recordIssueFailure(ReplacementCaseEntity c, String reason) {
        c.status = CaseStatus.FAILED;
        c.failureReason = reason;
        events.event(c, "ISSUE_FAILED",
                "制卡失败：" + reason + "；旧卡保持停用，案件记录保留，可重新发起");
    }
}
