package com.triplan.triplan.controller;

import com.triplan.triplan.dto.*;
import com.triplan.triplan.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.triplan.triplan.entity.User;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/plans")
@RequiredArgsConstructor
public class PlanController {

    private final PlanService planService;
    private final FileUploadService fileUploadService;
    private final CandidateService candidateService;
    private final DashboardService dashboardService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<PlanCreateResponse>> createPlan(
            @RequestPart(value = "file", required = false) MultipartFile file,
            @RequestPart(value = "chatLog", required = false) String chatLog,
            @RequestPart(value = "mission", required = false) String mission,
            @AuthenticationPrincipal User user) {

        String fileContent = (file != null && !file.isEmpty())
                ? fileUploadService.extractText(file) : null;

        String finalChatLog = (fileContent != null && chatLog != null)
                ? fileContent + "\n\n" + chatLog   // 둘 다 있으면 합치기
                : (fileContent != null ? fileContent : chatLog); // 하나만 있으면 그것만

        PlanCreateRequest request = new PlanCreateRequest(finalChatLog, mission);
        PlanCreateResponse response = planService.createPlan(request, user);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PostMapping("/{id}/generate")
    public ResponseEntity<ApiResponse<Void>> generate(
            @PathVariable Long id,
            @AuthenticationPrincipal User user) {
        planService.generateWithAI(id, user);
        return ResponseEntity.accepted().body(ApiResponse.ok(null));
    }

    @GetMapping("/{id}/status")
    public ResponseEntity<ApiResponse<Map<String, String>>> getStatus(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(planService.getStatus(id)));
    }

    @GetMapping("/{uuid}")
    public ResponseEntity<ApiResponse<PlanResponse>> getPlan(@PathVariable String uuid) {
        PlanResponse response = planService.getPlanByUuid(uuid);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @GetMapping("/{uuid}/candidates")
    public ResponseEntity<ApiResponse<List<CandidateResponse>>> getCandidates(@PathVariable String uuid) {
        List<CandidateResponse> response = candidateService.getCandidates(uuid);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @GetMapping("/{uuid}/dashboard")
    public ResponseEntity<ApiResponse<DashboardResponse>> getDashboard(
            @PathVariable String uuid,
            @AuthenticationPrincipal User user) {
        DashboardResponse response = dashboardService.getDashboard(uuid, user);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PatchMapping("/{uuid}/title")
    public ResponseEntity<ApiResponse<Void>> updateTitle(
            @PathVariable String uuid,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal User user) {
        planService.updateTitle(uuid, body.get("title"), user);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @PatchMapping("/{uuid}/share")
    public ResponseEntity<ApiResponse<Void>> sharePlan(
            @PathVariable String uuid,
            @AuthenticationPrincipal User user) {
        planService.sharePlan(uuid, user);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @PatchMapping("/{uuid}/claim")
    public ResponseEntity<ApiResponse<PlanResponse>> claimPlan(
            @PathVariable String uuid,
            @AuthenticationPrincipal User user) {
        PlanResponse response = planService.claimPlan(uuid, user);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PatchMapping("/{uuid}/places/order")
    public ResponseEntity<ApiResponse<Void>> updatePlaceOrders(
            @PathVariable String uuid,
            @RequestBody List<OrderPreferenceRequest> requests,
            @AuthenticationPrincipal User user) {
        planService.updatePlaceOrders(uuid, requests, user);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @PostMapping("/{uuid}/finalize")
    public ResponseEntity<ApiResponse<CandidateResponse>> finalizePlan(
            @PathVariable String uuid,
            @AuthenticationPrincipal User user) {
        CandidateResponse response = planService.finalizePlan(uuid, user);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    /** 생성된 추천 일정 조회 — S5 진입용 (POST /finalize 와 분리) */
    @GetMapping("/{uuid}/final")
    public ResponseEntity<ApiResponse<CandidateResponse>> getFinalPlan(@PathVariable String uuid) {
        CandidateResponse response = planService.getFinalCandidate(uuid);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @DeleteMapping("/{uuid}/candidates/{candidateId}/places/{placeId}")
    public ResponseEntity<ApiResponse<Void>> deletePlace(
            @PathVariable String uuid,
            @PathVariable Long candidateId,
            @PathVariable Long placeId,
            @AuthenticationPrincipal User user) {
        planService.deletePlace(uuid, candidateId, placeId, user);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @DeleteMapping("/{uuid}")
    public ResponseEntity<ApiResponse<Void>> deletePlan(
            @PathVariable String uuid,
            @AuthenticationPrincipal User user) {
        planService.deletePlan(uuid, user);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @DeleteMapping("/{uuid}/participation")
    public ResponseEntity<ApiResponse<Void>> leavePlan(
            @PathVariable String uuid,
            @AuthenticationPrincipal User user) {
        planService.leavePlan(uuid, user);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
