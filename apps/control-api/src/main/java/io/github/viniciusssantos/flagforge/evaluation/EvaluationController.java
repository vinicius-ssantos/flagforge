package io.github.viniciusssantos.flagforge.evaluation;

import io.github.viniciusssantos.flagforge.credentials.SdkCredentialService.SdkPrincipal;
import io.github.viniciusssantos.flagforge.evaluation.EvaluationApi.Request;
import io.github.viniciusssantos.flagforge.evaluation.EvaluationApi.Response;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/evaluate")
final class EvaluationController {

    private final EvaluationService evaluationService;

    EvaluationController(EvaluationService evaluationService) {
        this.evaluationService = evaluationService;
    }

    @PostMapping(
            path = "/{flagKey}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Response> evaluate(
            @PathVariable String flagKey,
            @RequestBody Request request,
            Authentication authentication) {
        if (!(authentication.getPrincipal() instanceof SdkPrincipal principal)) {
            throw new AccessDeniedException(
                    "SDK evaluation principal is required");
        }
        Response response = evaluationService.evaluate(
                principal,
                flagKey,
                request);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }
}
