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

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Handles inter-node HTTP communication for write replication.
 *
 * <p>Two operations are supported:
 * <ol>
 *   <li>{@link #forwardToLeader} — a follower proxies a client's write request
 *       verbatim to the leader and returns the leader's response to the
 *       originating client.</li>
 *   <li>{@link #replicateToFollowers} — after the leader commits a write it
 *       fans out the same request to every follower asynchronously (best-effort)
 *       so each node can apply the write to its local database.</li>
 * </ol>
 */
public interface ReplicationClient {

    /**
     * Proxies {@code request} (with pre-read {@code body}) to the given
     * {@code leader} node and writes the leader's HTTP response — status,
     * headers, and body — directly into {@code clientResponse}.
     *
     * <p>This method blocks until the leader responds or the request times out.
     *
     * @throws IOException if the leader cannot be reached or the response
     *                     cannot be written back to the client
     */
    void forwardToLeader(HttpServletRequest request, byte[] body,
                         HttpServletResponse clientResponse, NodeAddress leader) throws IOException;

    /**
     * Fans out the write described by {@code path}, {@code method},
     * {@code headers}, and {@code body} to each node in {@code followers}.
     *
     * <p>Each follower is contacted asynchronously on a background thread.
     * Failures are logged but do not block the caller; the leader's response
     * has already been sent to the client before this method is called.
     *
     * @param path      request path including any query string
     *                  (e.g. {@code /nifi-registry-api/buckets?param=value})
     * @param method    HTTP method (e.g. {@code POST}, {@code DELETE})
     * @param headers   original request headers to forward (hop-by-hop headers
     *                  are filtered out by the implementation)
     * @param body      request body bytes (may be empty)
     * @param followers destination nodes; must not include this node
     */
    void replicateToFollowers(String path, String method,
                               Map<String, String> headers, byte[] body,
                               List<NodeAddress> followers);
}
