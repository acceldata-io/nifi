/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.registry.event;

import org.apache.nifi.registry.bucket.Bucket;
import org.apache.nifi.registry.cluster.LeaderElectionManager;
import org.apache.nifi.registry.hook.Event;
import org.apache.nifi.registry.hook.EventHookException;
import org.apache.nifi.registry.hook.EventHookProvider;
import org.apache.nifi.registry.provider.ProviderConfigurationContext;
import org.apache.nifi.registry.provider.ProviderCreationException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ClusterAwareEventService} using an in-memory H2 database
 * with Flyway migrations applied programmatically (no Spring application context needed).
 */
public class TestClusterAwareEventService {

    private EmbeddedDatabase db;
    private JdbcTemplate jdbcTemplate;
    private LeaderElectionManager leaderElectionManager;
    private CapturingEventHook eventHook;
    private ClusterAwareEventService eventService;

    @BeforeEach
    public void setup() {
        // Build an in-memory H2 DB and apply the Flyway migrations.
        db = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .build();

        Flyway.configure()
                .dataSource(db)
                .locations("classpath:db/migration/common", "classpath:db/migration/default")
                .load()
                .migrate();

        jdbcTemplate = new JdbcTemplate(db);

        leaderElectionManager = Mockito.mock(LeaderElectionManager.class);
        eventHook = new CapturingEventHook();
        eventService = new ClusterAwareEventService(
                Collections.singletonList(eventHook),
                db,
                leaderElectionManager);
    }

    @AfterEach
    public void teardown() {
        db.shutdown();
    }

    // -------------------------------------------------------------------------
    // publish()
    // -------------------------------------------------------------------------

    @Test
    public void testPublishPersistsEvent() {
        final Bucket bucket = newBucket("b1");
        eventService.publish(EventFactory.bucketCreated(bucket));

        final int count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM REGISTRY_EVENT WHERE PROCESSED = FALSE", Integer.class);
        assertEquals(1, count, "One unprocessed event should be in the DB after publish()");
    }

    @Test
    public void testPublishNullEventIsIgnored() {
        eventService.publish(null);

        final int count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM REGISTRY_EVENT", Integer.class);
        assertEquals(0, count, "No event should be persisted for a null publish()");
    }

    @Test
    public void testPublishPersistsEventType() {
        final Bucket bucket = newBucket("b2");
        eventService.publish(EventFactory.bucketCreated(bucket));

        final String type = jdbcTemplate.queryForObject(
                "SELECT EVENT_TYPE FROM REGISTRY_EVENT", String.class);
        assertEquals("CREATE_BUCKET", type);
    }

    // -------------------------------------------------------------------------
    // Leader-only delivery
    // -------------------------------------------------------------------------

    @Test
    public void testDeliverySkippedWhenNotLeader() {
        when(leaderElectionManager.isLeader()).thenReturn(false);

        eventService.publish(EventFactory.bucketCreated(newBucket("b3")));
        invokeDeliverPendingEvents();

        assertTrue(eventHook.getEvents().isEmpty(),
                "No events should be delivered when this node is not the leader");

        final int unprocessed = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM REGISTRY_EVENT WHERE PROCESSED = FALSE", Integer.class);
        assertEquals(1, unprocessed, "Event should remain unprocessed when follower skips delivery");
    }

    @Test
    public void testDeliveryRunsWhenLeader() {
        when(leaderElectionManager.isLeader()).thenReturn(true);

        eventService.publish(EventFactory.bucketCreated(newBucket("b4")));
        invokeDeliverPendingEvents();

        assertEquals(1, eventHook.getEvents().size(),
                "Leader should deliver one event to the hook provider");

        final int unprocessed = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM REGISTRY_EVENT WHERE PROCESSED = FALSE", Integer.class);
        assertEquals(0, unprocessed, "Event should be marked processed after successful delivery");
    }

    // -------------------------------------------------------------------------
    // Retry-count behaviour
    // -------------------------------------------------------------------------

    @Test
    public void testRetryCountIncrementedOnProviderFailure() {
        when(leaderElectionManager.isLeader()).thenReturn(true);

        final ClusterAwareEventService failingService = new ClusterAwareEventService(
                Collections.singletonList(new FailingEventHook()), db, leaderElectionManager);

        failingService.publish(EventFactory.bucketCreated(newBucket("b5")));
        invokeDeliverPendingEvents(failingService);

        final int retryCount = jdbcTemplate.queryForObject(
                "SELECT RETRY_COUNT FROM REGISTRY_EVENT WHERE PROCESSED = FALSE", Integer.class);
        assertEquals(1, retryCount, "RETRY_COUNT should be 1 after first failure");
    }

    @Test
    public void testEventDiscardedAfterMaxRetries() {
        when(leaderElectionManager.isLeader()).thenReturn(true);

        final ClusterAwareEventService failingService = new ClusterAwareEventService(
                Collections.singletonList(new FailingEventHook()), db, leaderElectionManager);

        failingService.publish(EventFactory.bucketCreated(newBucket("b6")));

        for (int i = 0; i < ClusterAwareEventService.MAX_RETRY_COUNT; i++) {
            invokeDeliverPendingEvents(failingService);
        }

        final int unprocessed = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM REGISTRY_EVENT WHERE PROCESSED = FALSE", Integer.class);
        assertEquals(0, unprocessed,
                "Event should be marked processed (discarded) after MAX_RETRY_COUNT failures");
    }

    // -------------------------------------------------------------------------
    // Purge behaviour
    // -------------------------------------------------------------------------

    @Test
    public void testOldProcessedEventsArePurged() {
        when(leaderElectionManager.isLeader()).thenReturn(true);

        final Timestamp oldTimestamp = Timestamp.from(
                Instant.now().minus(ClusterAwareEventService.RETENTION_DAYS + 1, ChronoUnit.DAYS));
        jdbcTemplate.update(
                "INSERT INTO REGISTRY_EVENT (EVENT_ID, EVENT_TYPE, EVENT_DATA, CREATED_AT, PROCESSED) "
                        + "VALUES (?, 'CREATE_BUCKET', '{}', ?, TRUE)",
                UUID.randomUUID().toString(), oldTimestamp);

        invokeDeliverPendingEvents();

        final int afterPurge = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM REGISTRY_EVENT WHERE PROCESSED = TRUE", Integer.class);
        assertEquals(0, afterPurge, "Old processed events should be purged by the delivery loop");
    }

    @Test
    public void testRecentProcessedEventsAreNotPurged() {
        when(leaderElectionManager.isLeader()).thenReturn(true);

        jdbcTemplate.update(
                "INSERT INTO REGISTRY_EVENT (EVENT_ID, EVENT_TYPE, EVENT_DATA, CREATED_AT, PROCESSED) "
                        + "VALUES (?, 'CREATE_BUCKET', '{}', ?, TRUE)",
                UUID.randomUUID().toString(), Timestamp.from(Instant.now()));

        invokeDeliverPendingEvents();

        final int count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM REGISTRY_EVENT WHERE PROCESSED = TRUE", Integer.class);
        assertEquals(1, count, "Recently processed events should not be purged");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void invokeDeliverPendingEvents() {
        eventService.deliverPendingEvents();
    }

    private void invokeDeliverPendingEvents(final ClusterAwareEventService service) {
        service.deliverPendingEvents();
    }

    private static Bucket newBucket(final String name) {
        final Bucket bucket = new Bucket();
        bucket.setIdentifier(UUID.randomUUID().toString());
        bucket.setName(name);
        return bucket;
    }

    // -------------------------------------------------------------------------
    // Test hook implementations
    // -------------------------------------------------------------------------

    private static class CapturingEventHook implements EventHookProvider {
        private final List<Event> events = new ArrayList<>();

        @Override
        public void onConfigured(final ProviderConfigurationContext context) throws ProviderCreationException {
        }

        @Override
        public void handle(final Event event) throws EventHookException {
            events.add(event);
        }

        public List<Event> getEvents() {
            return events;
        }
    }

    private static class FailingEventHook implements EventHookProvider {
        @Override
        public void onConfigured(final ProviderConfigurationContext context) throws ProviderCreationException {
        }

        @Override
        public void handle(final Event event) throws EventHookException {
            throw new EventHookException("Simulated provider failure");
        }
    }
}
