package com.transit.web;

import com.transit.entity.CardEntity;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.*;

/** 卡片查询/充值（演示与对账用） */
@Path("/api/cards")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CardResource {

    @GET
    @Transactional
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (CardEntity c : CardEntity.<CardEntity>listAll()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.id);
            m.put("cardNo", c.cardNo);
            m.put("cardNoMasked", CaseQueryService.mask(c.cardNo));
            m.put("holderName", c.holderName);
            m.put("status", c.status.name());
            m.put("balanceCents", c.balanceCents);
            m.put("balanceText", CaseQueryService.money(c.balanceCents));
            out.add(m);
        }
        out.sort(Comparator.comparing(m -> (String) m.get("cardNo")));
        return out;
    }

    @GET
    @Path("/{cardNo}")
    @Transactional
    public Map<String, Object> get(@PathParam("cardNo") String cardNo) {
        CardEntity c = CardEntity.requireByNo(cardNo);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cardNo", c.cardNo);
        m.put("cardNoMasked", CaseQueryService.mask(c.cardNo));
        m.put("holderName", c.holderName);
        m.put("status", c.status.name());
        m.put("balanceCents", c.balanceCents);
        m.put("balanceText", CaseQueryService.money(c.balanceCents));
        return m;
    }
}
