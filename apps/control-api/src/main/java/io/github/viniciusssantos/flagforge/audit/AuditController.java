package io.github.viniciusssantos.flagforge.audit;

import java.util.List;
import java.util.UUID;

import io.github.viniciusssantos.flagforge.audit.AuditTrailService.AuditEvent;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/environments/{environmentId}/audit")
public class AuditController {

    private final AuditTrailService auditTrailService;

    public AuditController(AuditTrailService auditTrailService) {
        this.auditTrailService = auditTrailService;
    }

    @GetMapping
    List<AuditEvent> history(
            @PathVariable UUID environmentId,
            @RequestParam(defaultValue = "100") int limit) {
        return auditTrailService.history(environmentId, limit);
    }
}
