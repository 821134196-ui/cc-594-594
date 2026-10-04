package com.transit.web;

import com.transit.service.ClearingService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 本地清算中心模拟：批量回执。
 * 同一 receiptNo 重放 -> DUPLICATE，不重复结算/不重复转账。
 */
@Path("/api/clearing")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ClearingResource {

    @Inject ClearingService clearing;

    public static class LineReq {
        public String eventKey;
        public Long amountCents;
        public String occurredAt;  // ISO，可省略
        public String deviceId;
    }

    public static class ReceiptReq {
        public String caseNo;
        public String receiptNo;
        public List<LineReq> lines = new ArrayList<>();
    }

    @POST
    @Path("/receipt")
    public ClearingService.ReceiptResult receipt(ReceiptReq req) {
        if (req.caseNo == null || req.receiptNo == null) {
            throw new com.transit.service.ServiceException("caseNo 和 receiptNo 必填");
        }
        List<ClearingService.Line> lines = new ArrayList<>();
        for (LineReq l : req.lines) {
            ClearingService.Line line = new ClearingService.Line();
            line.eventKey = l.eventKey;
            line.amountCents = l.amountCents == null ? 0 : l.amountCents;
            line.occurredAt = l.occurredAt == null ? Instant.now() : Instant.parse(l.occurredAt);
            line.deviceId = l.deviceId;
            lines.add(line);
        }
        return clearing.acceptReceipt(req.caseNo, req.receiptNo, lines);
    }

    @GET
    @Path("/health")
    public Map<String, Object> health() {
        return Map.of("ok", true, "service", "local-clearing-sim");
    }
}
