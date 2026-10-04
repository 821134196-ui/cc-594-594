package com.transit.web;

import com.transit.service.GateService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;

/** 本地闸机模拟：刷卡（旧卡挂失/补卡后必须被拒绝） */
@Path("/api/gate")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class GateResource {

    @Inject GateService gates;

    public static class SwipeReq {
        public String cardNo;
        public Long fareCents;
        public String deviceId;
        public String eventKey;
    }

    @POST
    @Path("/swipe")
    public GateService.SwipeResult swipe(SwipeReq req) {
        if (req.cardNo == null || req.fareCents == null) {
            throw new com.transit.service.ServiceException("cardNo 和 fareCents 必填");
        }
        return gates.swipe(req.cardNo, req.fareCents,
                req.deviceId == null ? "GATE-SIM-01" : req.deviceId,
                req.eventKey == null ? ("SW" + System.nanoTime()) : req.eventKey);
    }
}
