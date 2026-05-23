package com.firstclub.membership.eligibility;

import com.firstclub.membership.common.persistence.BaseEntity;
import com.firstclub.membership.tier.Tier;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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
@Table(name = "tier_promotion_rules")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TierPromotionRule extends BaseEntity {

    @Column(name = "rule_type", nullable = false, length = 64)
    private String ruleType;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "target_tier_id", nullable = false)
    private Tier targetTier;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> config;

    @Column(nullable = false)
    private Integer priority;

    @Column(nullable = false)
    private Boolean active;
}
