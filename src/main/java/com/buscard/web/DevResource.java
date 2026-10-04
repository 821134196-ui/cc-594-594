package com.buscard.web;

import com.buscard.service.QueryService;
import com.buscard.service.SeedData;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.Map;

/** 演示辅助：重置数据、查看卡片列表。 */
@Path("/api/dev")
@Produces(MediaType.APPLICATION_JSON)
public class DevResource {

    @Inject
    EntityManager em;

    @Inject
    SeedData seedData;

    @Inject
    QueryService queryService;

    @POST
    @Path("/reset")
    @Transactional
    public Map<String, Object> reset() {
        em.createNativeQuery("DELETE FROM replacement_events").executeUpdate();
        em.createNativeQuery("DELETE FROM late_differences").executeUpdate();
        em.createNativeQuery("DELETE FROM card_transactions").executeUpdate();
        em.createNativeQuery("DELETE FROM offline_swipe_records").executeUpdate();
        em.createNativeQuery("DELETE FROM replacements").executeUpdate();
        em.createNativeQuery("DELETE FROM cards").executeUpdate();
        seedData.seed();
        return Map.of("reset", true, "demoCard", SeedData.DEMO_CARD,
                "idNumber", SeedData.DEMO_ID_NUMBER, "balance", "100.00");
    }

    @GET
    @Path("/cards")
    public Object cards() {
        return queryService.listCards();
    }
}
