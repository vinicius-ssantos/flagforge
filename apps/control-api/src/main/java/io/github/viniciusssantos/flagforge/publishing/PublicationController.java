package io.github.viniciusssantos.flagforge.publishing;

import java.util.UUID;

import io.github.viniciusssantos.flagforge.publishing.PublicationService.PublicationError;
import io.github.viniciusssantos.flagforge.publishing.PublicationService.PublicationException;
import io.github.viniciusssantos.flagforge.publishing.PublicationService.PublishedRevision;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/environments/{environmentId}/publication")
final class PublicationController {

    private final PublicationService publicationService;
    private final ChangeRequestService changeRequestService;

    PublicationController(
            PublicationService publicationService,
            ChangeRequestService changeRequestService) {
        this.publicationService = publicationService;
        this.changeRequestService = changeRequestService;
    }

    @PostMapping
    ResponseEntity<PublishedRevision> publish(
            @PathVariable UUID environmentId,
            @RequestBody PublishRequest request) {
        if (request.expectedVersion() == null) {
            throw new PublicationException(
                    PublicationError.INVALID_EXPECTED_VERSION,
                    "Expected publication version is required");
        }
        changeRequestService.requireDirectPublicationAllowed(environmentId);
        return ResponseEntity.ok(publicationService.publish(
                environmentId,
                request.expectedVersion()));
    }

    @GetMapping
    ResponseEntity<PublishedRevision> current(
            @PathVariable UUID environmentId) {
        return publicationService.current(environmentId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    record PublishRequest(Long expectedVersion) {
    }
}
