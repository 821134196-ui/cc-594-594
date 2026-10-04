package com.buscard.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 补卡办理进度事件，只追加不修改。乘客看到的进度时间线与客服的追踪依据都来自这里。
 */
@Entity
@Table(name = "replacement_events")
public class ReplacementEvent {

    @Id
    private String id;

    @Column(nullable = false)
    private String replacementId;

    @Column(nullable = false)
    private Instant at;

    /** REPORTED / SWIPE_REJECTED / PENDING_SETTLED / LATE_FARE / ISSUED / ISSUE_FAILED /
     *  TRANSFERRED / RE_REPORTED / DUPLICATE_RECEIPT 等 */
    @Column(nullable = false)
    private String type;

    @Column(nullable = false)
    private String message;

    /** 关联的交易号或回执号，便于客服逐笔追溯 */
    private String refId;

    public ReplacementEvent() {}

    public ReplacementEvent(String id, String replacementId, Instant at, String type, String message, String refId) {
        this.id = id;
        this.replacementId = replacementId;
        this.at = at;
        this.type = type;
        this.message = message;
        this.refId = refId;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getReplacementId() { return replacementId; }
    public void setReplacementId(String replacementId) { this.replacementId = replacementId; }
    public Instant getAt() { return at; }
    public void setAt(Instant at) { this.at = at; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getRefId() { return refId; }
    public void setRefId(String refId) { this.refId = refId; }
}
