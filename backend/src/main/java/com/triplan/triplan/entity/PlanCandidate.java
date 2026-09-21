package com.triplan.triplan.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "plan_candidates")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
public class PlanCandidate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id", nullable = false)
    private Plan plan;

    @Column(length = 10)
    private String label;

    @Column(length = 200)
    private String name;

    @Column(length = 300)
    private String concept;

    @Column(name = "split_reason", length = 500)
    private String splitReason;

    @Column(name = "estimated_cost_per_person")
    private Integer estimatedCostPerPerson;

    @Column(name = "total_distance_km", precision = 8, scale = 2)
    private BigDecimal totalDistanceKm;

    @Column(nullable = false)
    @Builder.Default
    private Integer version = 1;

    @Column(name = "is_final", nullable = false)
    @Builder.Default
    private Boolean isFinal = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public void updateEstimatedCostPerPerson(Integer estimatedCostPerPerson) {
        this.estimatedCostPerPerson = estimatedCostPerPerson;
    }
}
