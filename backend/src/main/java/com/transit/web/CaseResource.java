package com.transit.web;

import com.transit.service.CardFactoryService;
import com.transit.service.ReplacementService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.Map;

/**
 * 补卡案件。乘客查进度走 GET；客服操作走 report/issue/transfer/retry-clawback。
 */
@Path("/api/cases")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CaseResource {

    @Inject ReplacementService service;
    @Inject CaseQueryService query;
    @Inject CardFactoryService cardFactory;

    public static class ReportReq {
        public String cardNo;
        public String idNo;
        public String agentId;
    }

    @GET
    public List<Map<String, Object>> list() {
        return query.listCases();
    }

    @GET
    @Path("/{caseNo}")
    public CaseQueryService.CaseDetail detail(@PathParam("caseNo") String caseNo) {
        return query.detail(caseNo);
    }

    /** 客服：核验身份并受理挂失（旧卡即时停用） */
    @POST
    @Path("/report-loss")
    public CaseQueryService.CaseDetail report(ReportReq req) {
        if (req.cardNo == null || req.idNo == null) {
            throw new com.transit.service.ServiceException("cardNo 和 idNo 必填");
        }
        var c = service.reportLoss(req.cardNo, req.idNo,
                req.agentId == null ? "A001" : req.agentId);
        return query.detail(c.caseNo);
    }

    /** 客服：制发新卡（制卡失败时 HTTP 200 + ok:false，FAILED 记录已落库可查） */
    @POST
    @Path("/{caseNo}/issue-new-card")
    public java.util.Map<String, Object> issue(@PathParam("caseNo") String caseNo) {
        var r = service.issueNewCard(caseNo);
        var detail = query.detail(r.entity.caseNo);
        if (r.failed) {
            return java.util.Map.of("ok", false, "error", r.error, "case", detail);
        }
        return java.util.Map.of("ok", true, "case", detail);
    }

    /** 客服：在途清算完成后执行余额转移（失败也提交记录，返回 ok:false） */
    @POST
    @Path("/{caseNo}/transfer")
    public java.util.Map<String, Object> transfer(@PathParam("caseNo") String caseNo) {
        var r = service.transfer(caseNo);
        var detail = query.detail(r.entity.caseNo);
        if (r.failed) {
            return java.util.Map.of("ok", false, "error", r.error, "case", detail);
        }
        return java.util.Map.of("ok", true, "case", detail);
    }

    /** 客服：重试晚到差额追扣（新卡充值/恢复后） */
    @POST
    @Path("/{caseNo}/retry-clawback")
    public CaseQueryService.CaseDetail retry(@PathParam("caseNo") String caseNo) {
        service.retryClawbacks(caseNo);
        return query.detail(caseNo);
    }

    /** 演示开关：让下一次制卡失败 */
    @POST
    @Path("/demo/arm-card-factory-failure")
    public Map<String, Object> armFactoryFailure() {
        cardFactory.armFailure();
        return Map.of("armed", true, "effect", "下一次制卡将失败，案件记录保留为 FAILED");
    }

    /** 演示开关：让下一次余额转移失败 */
    @POST
    @Path("/demo/arm-transfer-failure")
    public Map<String, Object> armTransferFailure() {
        service.armTransferFailure();
        return Map.of("armed", true, "effect", "下一次转账将失败，旧卡不扣新卡不入，失败尝试留痕");
    }
}
