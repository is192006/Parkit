package com.parkit.domain.entity;

import com.parkit.domain.enums.ActorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/** Immutable audit record (table {@code audit_logs}, BR-27). */
@Entity
@Table(name = "audit_logs")
@EntityListeners(AuditingEntityListener.class)
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "log_id")
    private Long id;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 10, updatable = false)
    private ActorType actorType;

    /** Set iff actorType is USER (ck_actor). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_user_id", updatable = false)
    private User actor;

    @NotBlank
    @Size(max = 100)
    @Column(name = "action", nullable = false, length = 100, updatable = false)
    private String action;

    @NotBlank
    @Size(max = 50)
    @Column(name = "entity_type", nullable = false, length = 50, updatable = false)
    private String entityType;

    @Column(name = "entity_id", updatable = false)
    private Long entityId;

    /** Structured details (JSONB), e.g. old/new slot on reassignment. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details", columnDefinition = "jsonb", updatable = false)
    private Map<String, Object> details;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AuditLog() {
        // for JPA
    }

    private AuditLog(ActorType actorType, User actor, String action, String entityType,
                     Long entityId, Map<String, Object> details) {
        this.actorType = actorType;
        this.actor = actor;
        this.action = action;
        this.entityType = entityType;
        this.entityId = entityId;
        this.details = details;
    }

    /** Action performed by a logged-in user. */
    public static AuditLog byUser(User actor, String action, String entityType, Long entityId,
                                  Map<String, Object> details) {
        return new AuditLog(ActorType.USER, actor, action, entityType, entityId, details);
    }

    /** Action performed by the system (e.g. scheduler expiry). */
    public static AuditLog bySystem(String action, String entityType, Long entityId, Map<String, Object> details) {
        return new AuditLog(ActorType.SYSTEM, null, action, entityType, entityId, details);
    }

    /** Mirrors ck_actor. */
    @AssertTrue(message = "actor must be set exactly when actor type is USER")
    public boolean isActorConsistent() {
        return (actorType == ActorType.USER) == (actor != null);
    }

    public Long getId() {
        return id;
    }

    public ActorType getActorType() {
        return actorType;
    }

    public User getActor() {
        return actor;
    }

    public String getAction() {
        return action;
    }

    public String getEntityType() {
        return entityType;
    }

    public Long getEntityId() {
        return entityId;
    }

    public Map<String, Object> getDetails() {
        return details;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
