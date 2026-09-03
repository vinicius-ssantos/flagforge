package io.github.viniciusssantos.flagforge.evaluation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import io.github.viniciusssantos.flagforge.credentials.SdkCredentialService.SdkPrincipal;
import io.github.viniciusssantos.flagforge.evaluation.CurrentSnapshotSource.CurrentSnapshot;
import io.github.viniciusssantos.flagforge.evaluation.EvaluationApi.ValueType;
import io.github.viniciusssantos.flagforge.publishing.PublishedSnapshotCodec.PublishedFlag;
import io.github.viniciusssantos.flagforge.publishing.PublishedSnapshotCodec.PublishedValueType;
import io.github.viniciusssantos.flagforge.publishing.PublishedSnapshotCodec.PublishedVariant;

import org.springframework.stereotype.Component;

/**
 * Presents one flag's view of the environment's effective configuration.
 *
 * <p>Reading and caching the document belong to {@link CurrentSnapshotSource}; this narrows the
 * document to the requested flag. The split matters because every flag in an environment shares one
 * targeting graph — narrowing per request keeps a single copy of it in memory.
 */
@Component
public class DatabaseEvaluationSnapshotProvider
        implements EvaluationSnapshotProvider {

    private final CurrentSnapshotSource currentSnapshotSource;

    public DatabaseEvaluationSnapshotProvider(CurrentSnapshotSource currentSnapshotSource) {
        this.currentSnapshotSource = currentSnapshotSource;
    }

    @Override
    public Optional<EvaluationSnapshot> load(
            SdkPrincipal principal,
            String flagKey) {
        Optional<CurrentSnapshot> current = currentSnapshotSource.loadCurrent(
                principal.organizationId(),
                principal.environmentId());
        if (current.isEmpty()) {
            return Optional.empty();
        }

        CurrentSnapshot snapshot = current.get();
        Optional<PublishedFlag> requestedFlag = snapshot.document().flags().stream()
                .filter(flag -> flag.key().equals(flagKey))
                .findFirst();
        if (requestedFlag.isEmpty()) {
            return Optional.empty();
        }

        PublishedFlag flag = requestedFlag.get();
        String configurationVersion = "revision-"
                + snapshot.revisionNumber()
                + "-sha256-"
                + snapshot.checksum();

        return Optional.of(new EvaluationSnapshot(
                snapshot.document().organizationId(),
                snapshot.document().projectId(),
                snapshot.document().environmentId(),
                configurationVersion,
                flag.enabled(),
                snapshot.stale(),
                mapValueType(flag.valueType()),
                flag.defaultVariant(),
                mapVariants(flag),
                snapshot.document().targetingConfiguration(),
                null));
    }

    private static Map<String, VariantValue> mapVariants(PublishedFlag flag) {
        Map<String, VariantValue> variants = new LinkedHashMap<>();
        for (PublishedVariant variant : flag.variants()) {
            Object value = switch (variant.valueType()) {
                case BOOLEAN -> variant.booleanValue();
                case STRING -> variant.stringValue();
            };
            variants.put(
                    variant.key(),
                    new VariantValue(mapValueType(variant.valueType()), value));
        }
        return variants;
    }

    private static ValueType mapValueType(PublishedValueType valueType) {
        return switch (valueType) {
            case BOOLEAN -> ValueType.BOOLEAN;
            case STRING -> ValueType.STRING;
        };
    }
}
