package com.triplan.triplan.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Entity
@Table(name = "places")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
public class Place {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "candidate_id", nullable = false)
    private PlanCandidate candidate;

    @Column(name = "day_number", nullable = false)
    private Integer dayNumber;

    @Column(name = "order_index", nullable = false)
    private Integer orderIndex;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 50)
    private String category;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "estimated_cost")
    private Integer estimatedCost;

    @Column(name = "duration_minutes")
    private Integer durationMinutes;

    @Column(name = "visit_time")
    private LocalTime visitTime;

    @Column(precision = 9, scale = 6)
    private BigDecimal lat;

    @Column(precision = 9, scale = 6)
    private BigDecimal lng;

    @Column(name = "kakao_place_id", length = 100)
    private String kakaoPlaceId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * 카카오맵 검증 결과로 좌표와 place_id를 보정한다.
     */
    public void updateKakaoInfo(String kakaoPlaceId, BigDecimal lat, BigDecimal lng) {
        this.kakaoPlaceId = kakaoPlaceId;
        this.lat = lat;
        this.lng = lng;
    }

    public void updateSchedulePosition(Integer dayNumber, Integer orderIndex) {
        if (dayNumber != null) {
            this.dayNumber = dayNumber;
        }
        if (orderIndex != null) {
            this.orderIndex = orderIndex;
        }
    }
}
