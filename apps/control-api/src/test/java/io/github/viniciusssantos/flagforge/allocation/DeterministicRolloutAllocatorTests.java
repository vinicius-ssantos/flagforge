package io.github.viniciusssantos.flagforge.allocation;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.SplittableRandom;
import java.util.UUID;

import io.github.viniciusssantos.flagforge.allocation.DeterministicRolloutAllocator.AlgorithmVersion;
import io.github.viniciusssantos.flagforge.allocation.DeterministicRolloutAllocator.Allocation;
import io.github.viniciusssantos.flagforge.allocation.DeterministicRolloutAllocator.RolloutInput;
import io.github.viniciusssantos.flagforge.allocation.DeterministicRolloutAllocator.RolloutPercentage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class DeterministicRolloutAllocatorTests {

    private static final UUID ORGANIZATION_ID = UUID.fromString(
            "11111111-1111-1111-1111-111111111111");
    private static final UUID PROJECT_ID = UUID.fromString(
            "22222222-2222-2222-2222-222222222222");
    private static final UUID ENVIRONMENT_ID = UUID.fromString(
            "33333333-3333-3333-3333-333333333333");

    @Test
    void matchesPublishedCrossLanguageConformanceVectors() throws IOException {
        for (ConformanceVector vector : loadVectors()) {
            RolloutInput input = vector.toInput();
            Allocation allocation = DeterministicRolloutAllocator.allocate(input);

            assertThat(allocation.bucket())
                    .as(vector.caseId())
                    .isEqualTo(vector.expectedBucket());
            assertThat(allocation.sha256())
                    .as(vector.caseId())
                    .isEqualTo(vector.expectedSha256());
            assertThat(DeterministicRolloutAllocator.canonicalPayloadHex(input))
                    .as(vector.caseId())
                    .isEqualTo(vector.canonicalPayloadHex());
            assertThat(Normalizer.normalize(
                            vector.targetingKey(),
                            Normalizer.Form.NFC))
                    .as(vector.caseId())
                    .isEqualTo(vector.normalizedTargetingKey());
        }
    }

    @Test
    void appliesExactBoundarySemanticsForPercentageExpansion() throws IOException {
        ConformanceVector bucketZero = vector("bucket-zero");
        ConformanceVector lastAtTwenty = vector("twenty-percent-last-included");
        ConformanceVector firstAfterTwenty = vector(
                "twenty-percent-first-excluded");
        ConformanceVector bucketMaximum = vector("bucket-maximum");

        RolloutPercentage zero = RolloutPercentage.fromPercent(BigDecimal.ZERO);
        RolloutPercentage smallest = RolloutPercentage.fromPercent(
                new BigDecimal("0.001"));
        RolloutPercentage twenty = RolloutPercentage.fromPercent(
                new BigDecimal("20"));
        RolloutPercentage twentyAndOneUnit = RolloutPercentage.fromPercent(
                new BigDecimal("20.001"));
        RolloutPercentage almostAll = RolloutPercentage.fromPercent(
                new BigDecimal("99.999"));
        RolloutPercentage all = RolloutPercentage.fromPercent(
                new BigDecimal("100"));

        assertThat(DeterministicRolloutAllocator.isIncluded(
                bucketZero.toInput(), zero)).isFalse();
        assertThat(DeterministicRolloutAllocator.isIncluded(
                bucketZero.toInput(), smallest)).isTrue();
        assertThat(DeterministicRolloutAllocator.isIncluded(
                lastAtTwenty.toInput(), twenty)).isTrue();
        assertThat(DeterministicRolloutAllocator.isIncluded(
                firstAfterTwenty.toInput(), twenty)).isFalse();
        assertThat(DeterministicRolloutAllocator.isIncluded(
                firstAfterTwenty.toInput(), twentyAndOneUnit)).isTrue();
        assertThat(DeterministicRolloutAllocator.isIncluded(
                bucketMaximum.toInput(), almostAll)).isFalse();
        assertThat(DeterministicRolloutAllocator.isIncluded(
                bucketMaximum.toInput(), all)).isTrue();
    }

    @Test
    void generatedPropertySweepProvesDeterminismBoundsAndMonotonicExpansion() {
        SplittableRandom random = new SplittableRandom(0x5f3759dfL);
        String[] unicodeSuffixes = {"alpha", "José", "用户", "🚀"};

        for (int index = 0; index < 20_000; index++) {
            String targetingKey = "subject-"
                    + Long.toUnsignedString(random.nextLong(), 36)
                    + "-"
                    + unicodeSuffixes[index % unicodeSuffixes.length];
            RolloutInput input = input(targetingKey);

            Allocation first = DeterministicRolloutAllocator.allocate(input);
            Allocation second = DeterministicRolloutAllocator.allocate(input);
            int firstUnits = random.nextInt(
                    DeterministicRolloutAllocator.BUCKET_COUNT + 1);
            int secondUnits = random.nextInt(
                    DeterministicRolloutAllocator.BUCKET_COUNT + 1);
            RolloutPercentage smaller = new RolloutPercentage(
                    Math.min(firstUnits, secondUnits));
            RolloutPercentage larger = new RolloutPercentage(
                    Math.max(firstUnits, secondUnits));

            assertThat(first).isEqualTo(second);
            assertThat(first.bucket())
                    .isBetween(
                            0,
                            DeterministicRolloutAllocator.BUCKET_COUNT - 1);
            if (smaller.includes(first.bucket())) {
                assertThat(larger.includes(first.bucket())).isTrue();
            }
        }
    }

    @Test
    void deterministicSampleHasDocumentedDistributionQuality() {
        int sampleSize = 100_000;
        int[] deciles = new int[10];
        int includedAtTwentyPercent = 0;
        RolloutPercentage twenty = RolloutPercentage.fromPercent(
                new BigDecimal("20"));

        for (int index = 0; index < sampleSize; index++) {
            Allocation allocation = DeterministicRolloutAllocator.allocate(
                    input("sample-" + index));
            deciles[allocation.bucket() / 10_000]++;
            if (twenty.includes(allocation.bucket())) {
                includedAtTwentyPercent++;
            }
        }

        for (int count : deciles) {
            assertThat(count).isBetween(9_700, 10_300);
        }
        assertThat(includedAtTwentyPercent).isBetween(19_500, 20_500);
    }

    @Test
    void canonicalizesKeysAndUnicodeButKeepsTargetingWhitespaceSignificant() {
        RolloutInput canonical = input("José");
        RolloutInput equivalent = new RolloutInput(
                AlgorithmVersion.V1,
                ORGANIZATION_ID,
                PROJECT_ID,
                ENVIRONMENT_ID,
                " CHECKOUT-V2 ",
                " DEFAULT ",
                "José");
        RolloutInput spaced = input(" José ");

        assertThat(DeterministicRolloutAllocator.allocate(equivalent))
                .isEqualTo(DeterministicRolloutAllocator.allocate(canonical));
        assertThat(DeterministicRolloutAllocator.allocate(spaced))
                .isNotEqualTo(DeterministicRolloutAllocator.allocate(canonical));
    }

    @Test
    void validatesPercentagePrecisionAndInputBounds() {
        assertThat(RolloutPercentage.fromPercent(new BigDecimal("30.125")))
                .extracting(RolloutPercentage::units)
                .isEqualTo(30_125);
        assertThat(RolloutPercentage.fromPercent(new BigDecimal("30.125"))
                .percent()).isEqualByComparingTo("30.125");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> RolloutPercentage.fromPercent(
                        new BigDecimal("30.1255")))
                .withMessage("percent supports at most three decimal places");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RolloutPercentage.fromPercent(
                        new BigDecimal("100.001")))
                .withMessage("percent must be between 0 and 100");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DeterministicRolloutAllocator.allocate(
                        input(" ")))
                .withMessage("targetingKey cannot be blank");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DeterministicRolloutAllocator.allocate(
                        input("x".repeat(
                                DeterministicRolloutAllocator.MAX_TARGETING_KEY_BYTES
                                        + 1))))
                .withMessageContaining("UTF-8 bytes");
    }

    private static RolloutInput input(String targetingKey) {
        return new RolloutInput(
                AlgorithmVersion.V1,
                ORGANIZATION_ID,
                PROJECT_ID,
                ENVIRONMENT_ID,
                "checkout-v2",
                "default",
                targetingKey);
    }

    private static ConformanceVector vector(String caseId) throws IOException {
        return loadVectors().stream()
                .filter(vector -> vector.caseId().equals(caseId))
                .findFirst()
                .orElseThrow();
    }

    private static List<ConformanceVector> loadVectors() throws IOException {
        InputStream resource = Objects.requireNonNull(
                DeterministicRolloutAllocatorTests.class.getResourceAsStream(
                        "/conformance/rollout-v1.tsv"),
                "rollout-v1.tsv is required");
        List<ConformanceVector> vectors = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                resource,
                StandardCharsets.UTF_8))) {
            String line;
            boolean header = true;
            while ((line = reader.readLine()) != null) {
                if (header) {
                    header = false;
                    continue;
                }
                String[] values = line.split("\t", -1);
                if (values.length != 11) {
                    throw new IllegalStateException(
                            "Invalid conformance vector column count: " + line);
                }
                vectors.add(new ConformanceVector(
                        values[0],
                        UUID.fromString(values[1]),
                        UUID.fromString(values[2]),
                        UUID.fromString(values[3]),
                        values[4],
                        values[5],
                        values[6],
                        values[7],
                        values[8],
                        Integer.parseInt(values[9]),
                        values[10]));
            }
        }
        return List.copyOf(vectors);
    }

    private record ConformanceVector(
            String caseId,
            UUID organizationId,
            UUID projectId,
            UUID environmentId,
            String flagKey,
            String allocationKey,
            String targetingKey,
            String normalizedTargetingKey,
            String expectedSha256,
            int expectedBucket,
            String canonicalPayloadHex) {

        private RolloutInput toInput() {
            return new RolloutInput(
                    AlgorithmVersion.V1,
                    organizationId,
                    projectId,
                    environmentId,
                    flagKey,
                    allocationKey,
                    targetingKey);
        }
    }
}
