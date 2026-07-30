package io.github.viniciusssantos.flagforge.publishing;

import java.util.List;
import java.util.UUID;

import io.github.viniciusssantos.flagforge.publishing.ChangeRequestService.CandidateDiff;
import io.github.viniciusssantos.flagforge.publishing.ChangeRequestService.ChangeRequest;
import io.github.viniciusssantos.flagforge.publishing.EnvironmentApprovalPolicyService.ApprovalPolicy;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/environments/{environmentId}")
final class ChangeRequestController {

    private final ChangeRequestService changeRequestService;
    private final EnvironmentApprovalPolicyService policyService;

    ChangeRequestController(
            ChangeRequestService changeRequestService,
            EnvironmentApprovalPolicyService policyService) {
        this.changeRequestService = changeRequestService;
        this.policyService = policyService;
    }

    @GetMapping("/approval-policy")
    ApprovalPolicy policy(@PathVariable UUID environmentId) {
        return policyService.get(environmentId);
    }

    @PutMapping("/approval-policy")
    ApprovalPolicy updatePolicy(
            @PathVariable UUID environmentId,
            @RequestBody PolicyRequest request) {
        return policyService.update(
                environmentId,
                request.approvalRequired(),
                request.preventSelfApproval());
    }

    @PostMapping("/change-requests")
    ResponseEntity<ChangeRequest> create(
            @PathVariable UUID environmentId,
            @RequestBody CreateRequest request) {
        return ResponseEntity.ok(changeRequestService.create(
                environmentId,
                request.expectedPublicationVersion(),
                request.title(),
                request.description()));
    }

    @GetMapping("/change-requests")
    List<ChangeRequest> list(@PathVariable UUID environmentId) {
        return changeRequestService.list(environmentId);
    }

    @GetMapping("/change-requests/{changeRequestId}")
    ChangeRequest get(
            @PathVariable UUID environmentId,
            @PathVariable UUID changeRequestId) {
        return changeRequestService.get(environmentId, changeRequestId);
    }

    @GetMapping("/change-requests/{changeRequestId}/diff")
    CandidateDiff diff(
            @PathVariable UUID environmentId,
            @PathVariable UUID changeRequestId) {
        return changeRequestService.diff(environmentId, changeRequestId);
    }

    @PostMapping("/change-requests/{changeRequestId}/submit")
    ChangeRequest submit(
            @PathVariable UUID environmentId,
            @PathVariable UUID changeRequestId) {
        return changeRequestService.submit(environmentId, changeRequestId);
    }

    @PostMapping("/change-requests/{changeRequestId}/approve")
    ChangeRequest approve(
            @PathVariable UUID environmentId,
            @PathVariable UUID changeRequestId,
            @RequestBody DecisionRequest request) {
        return changeRequestService.approve(
                environmentId, changeRequestId, request.note());
    }

    @PostMapping("/change-requests/{changeRequestId}/reject")
    ChangeRequest reject(
            @PathVariable UUID environmentId,
            @PathVariable UUID changeRequestId,
            @RequestBody DecisionRequest request) {
        return changeRequestService.reject(
                environmentId, changeRequestId, request.note());
    }

    @PostMapping("/change-requests/{changeRequestId}/publish")
    ChangeRequest publish(
            @PathVariable UUID environmentId,
            @PathVariable UUID changeRequestId) {
        return changeRequestService.publish(environmentId, changeRequestId);
    }

    record PolicyRequest(
            boolean approvalRequired,
            boolean preventSelfApproval) {
    }

    record CreateRequest(
            long expectedPublicationVersion,
            String title,
            String description) {
    }

    record DecisionRequest(String note) {
    }
}
