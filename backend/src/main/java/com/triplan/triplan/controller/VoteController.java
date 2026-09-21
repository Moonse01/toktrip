package com.triplan.triplan.controller;

import com.triplan.triplan.dto.ApiResponse;
import com.triplan.triplan.dto.OrderPreferenceRequest;
import com.triplan.triplan.dto.PlanCommentRequest;
import com.triplan.triplan.dto.PlanCommentResponse;
import com.triplan.triplan.dto.VoteBatchRequest;
import com.triplan.triplan.dto.VoteBatchResponse;
import com.triplan.triplan.dto.VoteRequest;
import com.triplan.triplan.dto.VoteResponse;
import com.triplan.triplan.entity.User;
import com.triplan.triplan.service.PlanCommentService;
import com.triplan.triplan.service.VoteService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/plans/{uuid}/votes")  // ★ 변경
@RequiredArgsConstructor
public class VoteController {

    private final VoteService voteService;
    private final PlanCommentService planCommentService;

    @PostMapping
    public ResponseEntity<ApiResponse<VoteResponse>> vote(
            @PathVariable String uuid,
            @Valid @RequestBody VoteRequest request,
            @AuthenticationPrincipal User user,
            @RequestHeader(value = "X-Guest-UUID", required = false) String guestUuid) {
        VoteResponse response = voteService.vote(uuid, request, user, guestUuid);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PostMapping("/batch")
    public ResponseEntity<ApiResponse<VoteBatchResponse>> saveBatch(
            @PathVariable String uuid,
            @Valid @RequestBody VoteBatchRequest request,
            @AuthenticationPrincipal User user,
            @RequestHeader(value = "X-Guest-UUID", required = false) String guestUuid) {
        VoteBatchResponse response = voteService.saveBatch(uuid, request, user, guestUuid);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PostMapping("/comments")
    public ResponseEntity<ApiResponse<PlanCommentResponse>> addComment(
            @PathVariable String uuid,
            @Valid @RequestBody PlanCommentRequest request,
            @AuthenticationPrincipal User user,
            @RequestHeader(value = "X-Guest-UUID", required = false) String guestUuid) {
        PlanCommentResponse response = planCommentService.addComment(uuid, request, user, guestUuid);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PostMapping("/order")
    public ResponseEntity<ApiResponse<List<VoteResponse>>> saveOrderPreferences(
            @PathVariable String uuid,
            @Valid @RequestBody List<@Valid OrderPreferenceRequest> requests,
            @AuthenticationPrincipal User user,
            @RequestHeader(value = "X-Guest-UUID", required = false) String guestUuid) {
        List<VoteResponse> response = voteService.saveOrderPreferences(uuid, requests, user, guestUuid);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }
}
