package com.buscard.web;

import com.buscard.service.QueryService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * 乘客端接口：只看脱敏后的办理进度、新旧卡脱敏流水与余额转移状态。
 */
@Path("/api/passenger")
@Produces(MediaType.APPLICATION_JSON)
public class PassengerResource {

    @Inject
    QueryService queryService;

    @GET
    @Path("/cases/{id}")
    public QueryService.PassengerCase caseDetail(@PathParam("id") String id) {
        return queryService.passengerCase(id);
    }
}
