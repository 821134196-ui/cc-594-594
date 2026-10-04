package com.transit.web;

import com.transit.domain.Channel;
import com.transit.domain.TxnKind;
import com.transit.entity.CardEntity;
import com.transit.service.LedgerService;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import java.util.Map;

/** 充值（演示用：为余额不足的新卡充值后可完成挂账追扣） */
@Path("/api/cards")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class RechargeResource {

    @Inject LedgerService ledger;

    public static class Req {
        public Long amountCents;
    }

    @POST
    @Path("/{cardNo}/recharge")
    @Transactional
    public Map<String, Object> recharge(@PathParam("cardNo") String cardNo, Req req) {
        if (req.amountCents == null || req.amountCents <= 0) {
            throw new com.transit.service.ServiceException("amountCents 必须为正数");
        }
        CardEntity c = CardEntity.requireByNo(cardNo);
        ledger.recordSettled(c, TxnKind.RECHARGE, req.amountCents, Channel.SYSTEM,
                null, Instant.now(), null, "APP", null, null, "自助充值");
        return Map.of("cardNo", c.cardNo, "balanceCents", c.balanceCents,
                "balanceText", CaseQueryService.money(c.balanceCents));
    }
}
