package com.transit.bootstrap;

import com.transit.domain.Channel;
import com.transit.domain.TxnKind;
import com.transit.entity.CardEntity;
import com.transit.entity.ReplacementCaseEntity;
import com.transit.service.IdHasher;
import com.transit.service.LedgerService;
import com.transit.service.ReplacementService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 种子数据：全部在一个事务内顺序写入（SQLite 单连接池，禁止嵌套 REQUIRES_NEW）。
 *  A 张伟：余额 80.00 + 1 笔在途 2.00 —— 挂失主流程/等待清算
 *  B 李娜：补卡已完成，50.00 已转入新卡 —— 晚到交易/重复回执/旧卡拒刷
 *  C 王强：制卡成功但转账失败（FAILED 留痕）—— 失败记录保留、可重试
 */
@ApplicationScoped
public class SeedRunner {

    public static final String ID_A = "110101199001011234";
    public static final String ID_B = "310104199507204321";
    public static final String ID_C = "440305198812307654";

    @Inject IdHasher idHasher;
    @Inject LedgerService ledger;
    @Inject ReplacementService replacements;

    @Transactional
    public void seedIfEmpty() {
        if (CardEntity.count() > 0) return;
        Instant now = Instant.now();

        // ---------- A: 张伟（余额 80.00，1 笔在途 2.00） ----------
        CardEntity a = card("6200000000001001", "张伟", ID_A, now);
        settled(a, TxnKind.RECHARGE, 100_00, now.minus(30, ChronoUnit.DAYS), "R-A-1", "APP", "充值 100.00 元");
        settled(a, TxnKind.FARE, -2_00, now.minus(20, ChronoUnit.DAYS), "E-A-1", "GATE-BUS-20", "20 路乘车 2.00 元");
        settled(a, TxnKind.FARE, -3_00, now.minus(15, ChronoUnit.DAYS), "E-A-2", "GATE-METRO-03", "地铁乘车 3.00 元");
        settled(a, TxnKind.FARE, -5_00, now.minus(10, ChronoUnit.DAYS), "E-A-3", "GATE-AIR-01", "机场线 5.00 元");
        settled(a, TxnKind.FARE, -10_00, now.minus(2, ChronoUnit.DAYS), "E-A-4", "GATE-INTERCITY", "城际线 10.00 元");
        ledger.recordPendingFare(a, 2_00, "E-A-P1", now.minus(3, ChronoUnit.HOURS),
                "GATE-BUS-07", null, "今晨班车 2.00 元（在途，清算回执未到）");

        // ---------- B: 李娜（补卡已完成，50.00 在新卡） ----------
        CardEntity b = card("6200000000001002", "李娜", ID_B, now);
        settled(b, TxnKind.RECHARGE, 100_00, now.minus(40, ChronoUnit.DAYS), "R-B-1", "APP", "充值 100.00 元");
        settled(b, TxnKind.FARE, -50_00, now.minus(4, ChronoUnit.DAYS), "E-B-1", "GATE-METRO-08", "多次通勤合计 50.00 元");
        ReplacementCaseEntity cb = replacements.reportLoss("6200000000001002", ID_B, "A002");
        var ib = replacements.issueNewCard(cb.caseNo);
        replacements.transfer(ib.entity.caseNo);

        // ---------- C: 王强（转账失败留痕） ----------
        CardEntity c = card("6200000000001003", "王强", ID_C, now);
        settled(c, TxnKind.RECHARGE, 60_00, now.minus(12, ChronoUnit.DAYS), "R-C-1", "APP", "充值 60.00 元");
        ReplacementCaseEntity cc = replacements.reportLoss("6200000000001003", ID_C, "A001");
        var ic = replacements.issueNewCard(cc.caseNo);
        replacements.armTransferFailure();
        var failed = replacements.transfer(ic.entity.caseNo);
        if (!failed.failed) {
            throw new IllegalStateException("种子 C 场景期望转账失败，实际成功了");
        }
    }

    private CardEntity card(String no, String name, String idNo, Instant now) {
        CardEntity c = new CardEntity();
        c.cardNo = no;
        c.holderName = name;
        c.idHash = idHasher.hash(idNo);
        c.balanceCents = 0;
        c.issuedAt = now.minus(60, ChronoUnit.DAYS);
        c.persist();
        return c;
    }

    private void settled(CardEntity c, TxnKind kind, long signedCents, Instant at,
                         String eventKey, String device, String note) {
        ledger.recordSettled(c, kind, signedCents,
                kind == TxnKind.RECHARGE ? Channel.SYSTEM : Channel.GATE,
                eventKey, at, "SEED-RCPT", device, null, null, note);
    }
}
