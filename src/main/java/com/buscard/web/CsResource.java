package com.buscard.web;

import com.buscard.model.Replacement;
import com.buscard.service.QueryService;
import com.buscard.service.ReplacementService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

/**
 * 客服端接口：核身挂失、制卡（可注入故障）、手动转移、新卡再挂失、全量追踪。
 */
@Path("/api/cs")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CsResource {

    @Inject
    ReplacementService replacementService;

    @Inject
    QueryService queryService;

    /** 核身 + 挂失停用旧卡 */
    @POST
    @Path("/report-lost")
    public Replacement report(ReportReq req) {
        return replacementService.reportLost(req.cardId(), req.idNumber());
    }

    /** 制新卡；simulateFailure=true 时模拟制卡中心故障（单据保留、可重试） */
    @POST
    @Path("/issue")
    public Replacement issue(IssueReq req) {
        return replacementService.issueNewCard(req.replacementId(), Boolean.TRUE.equals(req.simulateFailure()));
    }

    /** 在途清零后手动转移（一般由清算回调自动完成） */
    @POST
    @Path("/transfer")
    public Replacement transfer(TransferReq req) {
        return replacementService.manualTransfer(req.replacementId(),
                req.receiptId() == null ? null : req.receiptId());
    }

    /** 新卡再次挂失：关闭上一单、另开新单沿卡链处理 */
    @POST
    @Path("/re-report-lost")
    public Replacement reReport(ReportReq req) {
        return replacementService.reReportLost(req.cardId(), req.idNumber());
    }

    @GET
    @Path("/cases")
    public List<Replacement> cases() {
        return queryService.listCases();
    }

    /** 客服全量追踪视图（明文卡号、逐笔流水与转移依据） */
    @GET
    @Path("/cases/{id}")
    public QueryService.AgentCase caseDetail(@PathParam("id") String id) {
        return queryService.agentCase(id);
    }

    public record ReportReq(String cardId, String idNumber) {}
    public record IssueReq(String replacementId, Boolean simulateFailure) {}
    public record TransferReq(String replacementId, String receiptId) {}
}
