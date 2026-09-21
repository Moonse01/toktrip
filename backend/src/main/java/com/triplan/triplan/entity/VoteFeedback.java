package com.triplan.triplan.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "votes_feedbacks")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
public class VoteFeedback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id", nullable = false)
    private Plan plan;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "candidate_id", nullable = false)
    private PlanCandidate candidate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "place_id", nullable = false)
    private Place place;

    @Column(name = "vote_score", nullable = false)
    @Builder.Default
    private Byte voteScore = 0;

    @Column(columnDefinition = "TEXT")
    private String comment;

    @Column(name = "preferred_day_number")
    private Integer preferredDayNumber;

    @Column(name = "preferred_order_index")
    private Integer preferredOrderIndex;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public void updateVote(Byte voteScore, String comment, Integer preferredDayNumber, Integer preferredOrderIndex) {
        this.voteScore = voteScore;
        // 투표 버튼만 다시 누른 요청이 기존 의견을 지우지 않도록, 의견이 온 경우에만 갱신한다.
        if (comment != null) {
            this.comment = comment;
        }
        // 순서 선호도는 전달된 경우 항상 갱신한다 (null이면 기존값 유지).
        if (preferredDayNumber != null) {
            this.preferredDayNumber = preferredDayNumber;
        }
        if (preferredOrderIndex != null) {
            this.preferredOrderIndex = preferredOrderIndex;
        }
    }

    public void updateOrderPreference(Integer preferredDayNumber, Integer preferredOrderIndex) {
        if (preferredDayNumber != null) this.preferredDayNumber = preferredDayNumber;
        if (preferredOrderIndex != null) this.preferredOrderIndex = preferredOrderIndex;
    }
}