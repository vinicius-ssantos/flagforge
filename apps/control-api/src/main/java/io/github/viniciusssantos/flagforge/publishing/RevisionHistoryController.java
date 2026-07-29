package io.github.viniciusssantos.flagforge.publishing;

import java.util.List;
import java.util.UUID;

import io.github.viniciusssantos.flagforge.publishing.PublicationService.PublicationError;
import io.github.viniciusssantos.flagforge.publishing.PublicationService.PublicationException;
import io.github.viniciusssantos.flagforge.publishing.PublicationService.PublishedRevision;
import io.github.viniciusssantos.flagforge.publishing.RevisionHistoryService.RevisionDiff;
import io.github.viniciusssantos.flagforge.publishing.RevisionHistoryService.RevisionSummary;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/environments/{environmentId}")
final class RevisionHistoryController {

    private final RevisionHistoryService revisionHistoryService;
    private final PublicationService publicationService;

    RevisionHistoryController(
            RevisionHistoryService revisionHistoryService,
            PublicationService publicationService) {
        this.revisionHistoryService = revisionHistoryService;
        this.publicationService = publicationService;
    }

    @GetMapping("/revisions")
    List<RevisionSummary> history(
            @PathVariable UUID environmentId,
            @RequestParam(defaultValue = "100") int limit) {
        return revisionHistoryService.history(environmentId, limit);
    }

    @GetMapping("/revisions/diff")
    RevisionDiff diff(
            @PathVariable UUID environmentId,
            @RequestParam long fromRevision,
            @RequestParam long toRevision) {
        return revisionHistoryService.diff(
                environmentId,
                fromRevision,
                toRevision);
    }

    @PostMapping("/rollback")
    ResponseEntity<PublishedRevision> rollback(
            @PathVariable UUID environmentId,
            @RequestBody RollbackRequest request) {
        if (request.sourceRevisionNumber() == null) {
            throw new PublicationException(
                    PublicationError.INVALID_ROLLBACK_SOURCE,
                    "Rollback source revision is required");
        }
        if (request.expectedVersion() == null) {
            throw new PublicationException(
                    PublicationError.INVALID_EXPECTED_VERSION,
                    "Expected publication version is required");
        }
        return ResponseEntity.ok(publicationService.rollback(
                environmentId,
                request.sourceRevisionNumber(),
                request.expectedVersion()));
    }

    record RollbackRequest(
            Long sourceRevisionNumber,
            Long expectedVersion) {
    }
}
