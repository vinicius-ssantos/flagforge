package io.github.viniciusssantos.flagforge;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.viniciusssantos.flagforge.distribution.ConfigurationChangeListener;
import io.github.viniciusssantos.flagforge.distribution.LatestPublishedVersionRegistry;
import io.github.viniciusssantos.flagforge.distribution.OutboxRelay;
import io.github.viniciusssantos.flagforge.distribution.PublishedConfigurationEvent;
import io.github.viniciusssantos.flagforge.flags.FeatureFlagService;
import io.github.viniciusssantos.flagforge.flags.FeatureFlagService.BooleanVariant;
import io.github.viniciusssantos.flagforge.flags.FeatureFlagService.CreateFlagCommand;
import io.github.viniciusssantos.flagforge.flags.FeatureFlagService.LifecycleType;
import io.github.viniciusssantos.flagforge.flags.FeatureFlagService.ValueType;
import io.github.viniciusssantos.flagforge.publishing.PublicationService;
import io.github.viniciusssantos.flagforge.tenancy.Environment;
import io.github.viniciusssantos.flagforge.tenancy.Organization;
import io.github.viniciusssantos.flagforge.tenancy.Project;
import io.github.viniciusssantos.flagforge.tenancy.TenantHierarchyService;
import io.github.viniciusssantos.flagforge.tenancy.TenantPrincipal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises delivery guarantees against a real database.
 *
 * <p>The relay is driven directly rather than through its scheduler: the guarantees under test are
 * about what delivery does, not about when it happens, and waiting on a background thread would
 * only make the assertions slower and flakier.
 *
 * <p>Every assertion is scoped to this test's own environment. The database is shared with the rest
 * of the suite, so earlier tests leave their own undelivered events behind and any assertion on a
 * global count would depend on execution order.
 */
@SpringBootTest
class OutboxRelayIntegrationTests extends PostgreSqlIntegrationTestSupport {

    private static final int DRAIN_LIMIT = 20;

    @Autowired
    private OutboxRelay outboxRelay;

    @Autowired
    private RecordingListener recordingListener;

    @Autowired
    private LatestPublishedVersionRegistry versionRegistry;

    @Autowired
    private PublicationService publicationService;

    @Autowired
    private TenantHierarchyService tenantHierarchyService;

    @Autowired
    private FeatureFlagService featureFlagService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetListener() {
        recordingListener.reset();
        versionRegistry.clear();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void deliversAPublishedVersionWithItsFullIdentity() {
        Fixture fixture = publishOnce("delivers");

        drainRelay();

        List<PublishedConfigurationEvent> delivered = recordingListener.receivedFor(fixture.environmentId());
        assertThat(delivered).hasSize(1);
        PublishedConfigurationEvent event = delivered.getFirst();
        assertThat(event.organizationId()).isEqualTo(fixture.organizationId());
        assertThat(event.revisionNumber()).isEqualTo(1);
        assertThat(event.checksum()).matches("[0-9a-f]{64}");
        assertThat(event.occurredAt()).isNotNull();
        assertThat(status(event.eventId())).isEqualTo("DELIVERED");
        assertThat(versionRegistry.latestRevisionNumber(fixture.environmentId())).contains(1L);
    }

    @Test
    void deliveredEventsAreNotDeliveredAgain() {
        Fixture fixture = publishOnce("once");

        drainRelay();
        drainRelay();

        assertThat(recordingListener.receivedFor(fixture.environmentId())).hasSize(1);
    }

    @Test
    void duplicateDeliveryLeavesTheConsumerUnchanged() {
        Fixture fixture = publishOnce("duplicate");
        drainRelay();
        PublishedConfigurationEvent event =
                recordingListener.receivedFor(fixture.environmentId()).getFirst();

        // Redelivering the same event is what an at-least-once relay does after a crash.
        versionRegistry.onConfigurationPublished(event);
        versionRegistry.onConfigurationPublished(event);

        assertThat(versionRegistry.latestRevisionNumber(fixture.environmentId())).contains(1L);
    }

    @Test
    void anOlderEventCannotRegressTheRecordedVersion() {
        Fixture fixture = publishOnce("ordering");
        drainRelay();
        PublishedConfigurationEvent first =
                recordingListener.receivedFor(fixture.environmentId()).getFirst();

        versionRegistry.onConfigurationPublished(withRevisionNumber(first, 7));
        versionRegistry.onConfigurationPublished(withRevisionNumber(first, 3));

        assertThat(versionRegistry.latestRevisionNumber(fixture.environmentId())).contains(7L);
    }

    @Test
    void aFailingConsumerLeavesThePublicationDurableAndTheEventRetryable() {
        Fixture fixture = publishOnce("retry");
        UUID eventId = onlyEventId(fixture.environmentId());
        recordingListener.failNext(true);

        outboxRelay.deliverPending();

        assertThat(recordingListener.receivedFor(fixture.environmentId())).isEmpty();
        assertThat(status(eventId)).isEqualTo("PENDING");
        assertThat(attempts(eventId)).isEqualTo(1);
        assertThat(isBackedOffBeyondPublication(eventId)).isTrue();

        // The publication itself is untouched by a distribution failure.
        assertThat(count("configuration_revisions", fixture.environmentId())).isEqualTo(1);
        assertThat(count("configuration_snapshots", fixture.environmentId())).isEqualTo(1);
        assertThat(publicationService.current(fixture.environmentId()).orElseThrow().revisionNumber())
                .isEqualTo(1);

        // A backed-off event is not claimable until its time comes.
        outboxRelay.deliverPending();
        assertThat(attempts(eventId)).isEqualTo(1);

        recordingListener.failNext(false);
        makeAvailableNow(eventId);
        drainRelay();

        assertThat(status(eventId)).isEqualTo("DELIVERED");
        assertThat(attempts(eventId)).isEqualTo(2);
        assertThat(recordingListener.receivedFor(fixture.environmentId())).hasSize(1);
    }

    @Test
    void anEventIsAbandonedAfterItsAttemptsAreExhausted() {
        Fixture fixture = publishOnce("abandon");
        UUID eventId = onlyEventId(fixture.environmentId());
        recordingListener.failNext(true);

        int maxAttempts = 8;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            makeAvailableNow(eventId);
            outboxRelay.deliverPending();
        }

        assertThat(status(eventId)).isEqualTo("FAILED");
        assertThat(attempts(eventId)).isEqualTo(maxAttempts);

        // A dead event is never claimed again, even once it would be available.
        recordingListener.failNext(false);
        makeAvailableNow(eventId);
        drainRelay();

        assertThat(status(eventId)).isEqualTo("FAILED");
        assertThat(recordingListener.receivedFor(fixture.environmentId())).isEmpty();
    }

    @Test
    void pendingEventsSurviveUntilARelayRunsAgain() {
        Fixture fixture = publishOnce("recovery");
        UUID eventId = onlyEventId(fixture.environmentId());

        // Nothing ran yet: the row is durable and still waiting, which is what a restart looks like.
        assertThat(status(eventId)).isEqualTo("PENDING");
        assertThat(attempts(eventId)).isZero();

        drainRelay();

        assertThat(status(eventId)).isEqualTo("DELIVERED");
    }

    @Test
    void aRollbackIsDeliveredAsANewEffectiveVersion() {
        Fixture fixture = publishOnce("rollback");
        publicationService.publish(fixture.environmentId(), 1);
        publicationService.rollback(fixture.environmentId(), 1, 2);

        drainRelay();

        assertThat(recordingListener.receivedFor(fixture.environmentId())).hasSize(3);
        assertThat(versionRegistry.latestRevisionNumber(fixture.environmentId())).contains(3L);
    }

    /**
     * Runs passes until nothing more is deliverable.
     *
     * <p>Bounded so a permanently failing event cannot spin here forever.
     */
    private void drainRelay() {
        for (int pass = 0; pass < DRAIN_LIMIT && outboxRelay.deliverPending() > 0; pass++) {
            continue;
        }
    }

    private Fixture publishOnce(String prefix) {
        String actorId = "actor-" + UUID.randomUUID();
        String slug = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        Organization organization = tenantHierarchyService.registerOrganization(
                slug,
                prefix + " organization",
                actorId);
        authenticate(organization.id(), actorId);
        Project project = tenantHierarchyService.createProject(prefix + "-project", prefix);
        Environment environment = tenantHierarchyService.createEnvironment(
                project.id(),
                "production",
                "Production");
        featureFlagService.create(new CreateFlagCommand(
                project.id(),
                "checkout-v2",
                "Checkout V2",
                null,
                actorId,
                ValueType.BOOLEAN,
                LifecycleType.OPERATIONAL,
                null,
                "disabled",
                List.of(
                        new BooleanVariant("disabled", false),
                        new BooleanVariant("enabled", true))));
        publicationService.publish(environment.id(), 0);
        return new Fixture(organization.id(), environment.id());
    }

    private static void authenticate(UUID organizationId, String actorId) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        new TenantPrincipal(organizationId, actorId),
                        null,
                        List.of()));
    }

    private static PublishedConfigurationEvent withRevisionNumber(
            PublishedConfigurationEvent event,
            long revisionNumber) {
        return new PublishedConfigurationEvent(
                UUID.randomUUID(),
                event.organizationId(),
                event.projectId(),
                event.environmentId(),
                event.revisionId(),
                revisionNumber,
                event.checksum(),
                event.occurredAt());
    }

    private UUID onlyEventId(UUID environmentId) {
        return jdbcTemplate.queryForObject(
                "select id from flagforge.configuration_outbox where environment_id = ?",
                UUID.class,
                environmentId);
    }

    private String status(UUID eventId) {
        return jdbcTemplate.queryForObject(
                "select status from flagforge.configuration_outbox where id = ?",
                String.class,
                eventId);
    }

    private int attempts(UUID eventId) {
        Integer attempts = jdbcTemplate.queryForObject(
                "select delivery_attempts from flagforge.configuration_outbox where id = ?",
                Integer.class,
                eventId);
        return attempts == null ? 0 : attempts;
    }

    /**
     * Compares the two timestamps the application itself wrote.
     *
     * <p>Comparing against the database's {@code now()} would make this depend on clock skew
     * between the JVM and the PostgreSQL container.
     */
    private boolean isBackedOffBeyondPublication(UUID eventId) {
        Boolean pushedOut = jdbcTemplate.queryForObject(
                "select available_at > occurred_at from flagforge.configuration_outbox where id = ?",
                Boolean.class,
                eventId);
        return Boolean.TRUE.equals(pushedOut);
    }

    /**
     * Brings a backed-off event forward, standing in for the passage of time.
     *
     * <p>Set well into the past rather than to {@code now()} so the row is claimable regardless of
     * any skew between the container's clock and the application's.
     */
    private void makeAvailableNow(UUID eventId) {
        jdbcTemplate.update(
                "update flagforge.configuration_outbox"
                        + " set available_at = now() - interval '1 hour' where id = ?",
                eventId);
    }

    private int count(String table, UUID environmentId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from flagforge." + table + " where environment_id = ?",
                Integer.class,
                environmentId);
        return count == null ? 0 : count;
    }

    private record Fixture(UUID organizationId, UUID environmentId) {
    }

    @TestConfiguration
    static class ListenerConfiguration {

        @Bean
        RecordingListener recordingListener() {
            return new RecordingListener();
        }
    }

    /**
     * A listener whose failure can be switched on, standing in for a degraded consumer.
     */
    static final class RecordingListener implements ConfigurationChangeListener {

        private final List<PublishedConfigurationEvent> received = new CopyOnWriteArrayList<>();
        private final AtomicBoolean failing = new AtomicBoolean();

        @Override
        public void onConfigurationPublished(PublishedConfigurationEvent event) {
            if (failing.get()) {
                throw new IllegalStateException("consumer unavailable");
            }
            received.add(event);
        }

        void failNext(boolean failing) {
            this.failing.set(failing);
        }

        List<PublishedConfigurationEvent> receivedFor(UUID environmentId) {
            return received.stream()
                    .filter(event -> event.environmentId().equals(environmentId))
                    .toList();
        }

        void reset() {
            received.clear();
            failing.set(false);
        }
    }
}
