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

import org.apache.nifi.registry.hook.Event;

/**
 * Service used for publishing events and passing them to configured hook providers.
 *
 * <p>In standalone mode (the default) a {@link StandardEventService} is used: events
 * are queued in memory and delivered on a single background thread.
 *
 * <p>In cluster mode ({@code nifi.registry.cluster.enabled=true}) a
 * {@link ClusterAwareEventService} is used: events are persisted to the
 * {@code REGISTRY_EVENT} database table and delivered exactly once by the
 * current leader node.
 *
 * <p>The active implementation is selected by {@link EventServiceConfiguration}.
 */
public interface EventService {

    /**
     * Publishes the given event for delivery to all configured
     * {@link org.apache.nifi.registry.hook.EventHookProvider}s.
     *
     * <p>Null events and invalid events (those that fail {@link Event#validate()})
     * are silently discarded.
     *
     * @param event the event to publish
     */
    void publish(Event event);
}
