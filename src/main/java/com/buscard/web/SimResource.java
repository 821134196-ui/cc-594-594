package com.buscard.web;

import com.buscard.model.OfflineSwipeRecord;
import com.buscard.service.GateService;
import com.buscard.service.QueryService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 闸机与清算模拟接口。
 */
@Path("/api/sim")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SimResource {

    @Inject
    GateService gateService;

    @Inject
    QueryService queryService;

    /** 在线刷卡（实时鉴权） */
    @POST
    @Path("/swipes/online")
    public GateService.GateSwipeResult onlineSwipe(SwipeReq req) {
        return gateService.onlineSwipe(req.cardId(), new BigDecimal(req.amount()),
                req.gateId() == null ? "GATE-A" : req.gateId());
    }

    /** 离线刷卡（闸机本地记账，不同步不扣款）。minutesAgo 可把乘车时间钉在过去，用于演示晚到消费。 */
    @POST
    @Path("/swipes/offline")
    public Map<String, Object> offlineSwipe(SwipeReq req) {
        java.time.Instant at = req.minutesAgo() != null
                ? java.time.Instant.now().minusSeconds(Math.round(Double.parseDouble(req.minutesAgo()) * 60))
                : null;
        OfflineSwipeRecord rec = gateService.offlineSwipeAt(req.cardId(), new BigDecimal(req.amount()),
                req.gateId() == null ? "GATE-B" : req.gateId(), at);
        return Map.of("pendingRef", rec.getPendingRef(), "cardId", rec.getCardId(),
                "gateId", rec.getGateId(), "amount", rec.getAmount().toPlainString(),
                "occurredAt", rec.getOccurredAt().toString(), "synced", false);
    }

    /** 闸机恢复联网，把本地队列同步到中心（成为 PENDING 在途流水） */
    @POST
    @Path("/gates/sync")
    public Map<String, Object> sync(@QueryParam("gateId") String gateId) {
        int n = gateService.syncGate(gateId);
        return Map.of("synced", n);
    }

    /** 清算中心凭回执清算一笔在途消费；重复回执幂等 */
    @POST
    @Path("/clearing")
    public GateService.ClearResult clear(ClearingReq req) {
        return gateService.clear(req.pendingRef(), req.receiptId());
    }

    @GET
    @Path("/offline-records")
    public List<OfflineSwipeRecord> offline(@QueryParam("gateId") String gateId) {
        return queryService.listOffline(gateId);
    }

    public record SwipeReq(String cardId, String amount, String gateId, String minutesAgo) {}
    public record ClearingReq(String pendingRef, String receiptId) {}
}
