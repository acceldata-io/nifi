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
package org.apache.nifi.registry.cluster;

/**
 * Provides leader-election semantics for NiFi Registry cluster nodes.
 *
 * <p>In a single-node deployment the implementation always returns {@code false}
 * from {@link #isLeader()} (the node is not participating in election). When
 * {@code nifi.registry.cluster.enabled=true} the {@link DatabaseLeaderElectionManager}
 * implementation performs TTL-based leader election via the {@code CLUSTER_LEADER}
 * database table so that exactly one node is leader at any point in time.
 */
public interface LeaderElectionManager {

    /**
     * Returns {@code true} if this node currently holds the leader lease.
     *
     * <p>The value is updated by a background heartbeat thread and may lag
     * reality by up to the heartbeat interval (default 10 s). Callers that
     * need a guaranteed-current answer should prefer a fresh DB query.
     */
    boolean isLeader();
}
