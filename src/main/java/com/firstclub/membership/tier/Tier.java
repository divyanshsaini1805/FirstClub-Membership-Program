package com.firstclub.membership.tier;

import com.firstclub.membership.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@Entity
@Table(name = "tiers")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Tier extends BaseEntity {

    @Column(nullable = false, unique = true, length = 32)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(length = 512)
    private String description;

    /** Higher = better. Drives upgrade/downgrade comparisons. */
    @Column(name = "rank", nullable = false)
    private Integer rank;

    @Column(nullable = false)
    private BigDecimal price;

    @Column(nullable = false)
    private Boolean active;
}
