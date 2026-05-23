package com.firstclub.membership.subscription;

import com.firstclub.membership.common.persistence.AppendOnlyEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;

@Getter
@Setter
@Entity
@Table(name = "subscription_events")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubscriptionEvent extends AppendOnlyEntity {

    public enum Type {
        CREATED,
        TIER_UPGRADED,
        TIER_DOWNGRADE_SCHEDULED,
        TIER_DOWNGRADE_APPLIED,
        CANCELLED,
        EXPIRED,
        AUTO_PROMOTED,
        AUTO_PROMOTION_EXPIRED,
        RENEWED
    }

    public enum Actor { USER, SYSTEM, ADMIN }

    @Column(name = "subscription_id", nullable = false)
    private Long subscriptionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 64)
    private Type type;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Enumerated(EnumType.STRING)
    @Column(length = 64)
    private Actor actor;
}
