package com.transit.service;

import com.transit.entity.CaseEventEntity;
import com.transit.entity.ReplacementCaseEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.Instant;

/** 案件事件流记录器（只增不改） */
@ApplicationScoped
public class CaseEventRecorder {

    @Transactional
    public void event(ReplacementCaseEntity c, String code, String detail, String refType, Long refId) {
        CaseEventEntity e = new CaseEventEntity();
        e.caseId = c.id;
        e.code = code;
        e.detail = detail;
        e.refType = refType;
        e.refId = refId;
        e.at = Instant.now();
        e.persist();
    }

    public void event(ReplacementCaseEntity c, String code, String detail) {
        event(c, code, detail, null, null);
    }
}
