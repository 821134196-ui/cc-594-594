package com.transit.web;

import com.transit.bootstrap.SeedRunner;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.Map;

/** 演示辅助：重置回种子数据（清空全部业务表后重新播种） */
@Path("/api/demo")
@Produces(MediaType.APPLICATION_JSON)
public class DemoResource {

    private static final List<String> TABLES = List.of(
            "late_adjustment", "balance_transfer", "clearing_receipt", "case_event",
            "txn", "replacement_case", "card");

    @Inject EntityManager em;
    @Inject SeedRunner seedRunner;

    @POST
    @Path("/reset")
    @Transactional
    public Map<String, Object> reset() {
        for (String t : TABLES) em.createNativeQuery("delete from " + t).executeUpdate();
        Long seq = ((Number) em.createNativeQuery(
                "select count(*) from sqlite_master where type='table' and name='sqlite_sequence'")
                .getSingleResult()).longValue();
        if (seq > 0) em.createNativeQuery("delete from sqlite_sequence").executeUpdate();
        em.flush();
        em.clear();
        seedRunner.seedIfEmpty();
        return Map.of("reset", true, "note", "已恢复为三张种子卡（A 挂失中/B 已补卡/C 转账失败留痕）");
    }
}
