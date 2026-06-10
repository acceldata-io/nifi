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

import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * {@link ReplicationClient} backed by Apache {@link CloseableHttpClient} (JDK 8 compatible).
 *
 * <p>A single shared {@link CloseableHttpClient} instance is used for all requests; it is thread-safe
 * and manages its own connection pool.
 *
 * <p>Fan-out to followers is performed on a cached-thread-pool executor so
 * it does not block the leader's response to the original client. Failures
 * are logged; the implementation does not retry (a future phase can add
 * retry/reconciliation logic).
 *
 * <p>Hop-by-hop headers (RFC 7230 §6.1) are stripped before forwarding to
 * avoid protocol errors on independent TCP connections.
 */
public class HttpReplicationClient implements ReplicationClient, DisposableBean {

    private static final Logger LOGGER = LoggerFactory.getLogger(HttpReplicationClient.class);

    /** Header added to fan-out requests so followers skip re-forwarding. */
    public static final String REPLICATION_HEADER = "X-Registry-Replication";
    /** Shared-secret header verifying that a replicated request comes from the leader. */
    public static final String INTERNAL_AUTH_HEADER = "X-Registry-Internal-Auth";

    private static final Set<String> HOP_BY_HOP = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "connection", "keep-alive", "transfer-encoding", "te",
                    "trailers", "upgrade", "proxy-authorization", "proxy-authenticate",
                    "content-length"
            ))
    );

    /**
     * Headers from the original browser request that must NOT be forwarded to followers.
     *
     * <p>{@code cookie} — The browser's session cookies (including the CSRF token cookie)
     * must be stripped.  If forwarded, the follower's {@code CsrfRequestMatcher} sees the
     * CSRF cookie and requires a CSRF token header, which the server-to-server fan-out
     * request does not carry, causing a 403 that silently drops the replication.
     *
     * <p>{@code origin} — A forwarded Origin header would trigger CORS processing on the
     * follower against the wrong request context.
     */
    private static final Set<String> SESSION_HEADERS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("cookie", "origin"))
    );

    private static final int CONNECT_TIMEOUT_MILLIS = (int) TimeUnit.SECONDS.toMillis(5);
    private static final int REQUEST_TIMEOUT_MILLIS = (int) TimeUnit.SECONDS.toMillis(30);

    private final String authToken;
    private final CloseableHttpClient httpClient;
    private final ExecutorService fanOutExecutor;

    public HttpReplicationClient(final String authToken) {
        this.authToken = authToken;
        final RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(CONNECT_TIMEOUT_MILLIS)
                .setConnectionRequestTimeout(REQUEST_TIMEOUT_MILLIS)
                .setSocketTimeout(REQUEST_TIMEOUT_MILLIS)
                .build();

        this.httpClient = HttpClients.custom()
                .setDefaultRequestConfig(requestConfig)
                .build();
        this.fanOutExecutor = Executors.newCachedThreadPool(r -> {
            final Thread t = new Thread(r, "registry-fanout");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void destroy() {
        fanOutExecutor.shutdown();
        try {
            httpClient.close();
        } catch (final IOException e) {
            LOGGER.debug("Error closing replication HTTP client", e);
        }

        try {
            if (!fanOutExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                fanOutExecutor.shutdownNow();
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // -------------------------------------------------------------------------
    // ReplicationClient
    // -------------------------------------------------------------------------

    @Override
    public void replicateToFollowers(final String path, final String method,
            final Map<String, String> headers, final byte[] body,
            final List<NodeAddress> followers) {

        for (final NodeAddress follower : followers) {
            fanOutExecutor.submit(() -> replicateToOne(path, method, headers, body, follower));
        }
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private void replicateToOne(final String path, final String method,
            final Map<String, String> headers, final byte[] body, final NodeAddress follower) {
        final String url = buildUrl(follower.getBaseUrl(), path, null);
        LOGGER.debug("Replicating {} {} to follower '{}'.", method, path, follower.getNodeId());

        try {
            final HttpRequestBase request = createRequest(method, url, body);
            request.addHeader(REPLICATION_HEADER, "true");
            request.addHeader(INTERNAL_AUTH_HEADER, authToken);

            headers.forEach((name, value) -> {
                final String lower = name.toLowerCase(Locale.ROOT);
                if (!HOP_BY_HOP.contains(lower)
                        && !SESSION_HEADERS.contains(lower)
                        && !REPLICATION_HEADER.equalsIgnoreCase(name)
                        && !INTERNAL_AUTH_HEADER.equalsIgnoreCase(name)) {
                    try {
                        request.addHeader(name, value);
                    } catch (final IllegalArgumentException e) {
                        // Skip restricted headers (e.g. host).
                    }
                }
            });

            try (CloseableHttpResponse response = httpClient.execute(request)) {
                final int statusCode = response.getStatusLine().getStatusCode();
                EntityUtils.consumeQuietly(response.getEntity());

                if (statusCode >= 400) {
                    // 404 on DELETE is idempotent (resource already absent on follower); log at DEBUG.
                    // All other 4xx/5xx responses indicate a genuine replication failure.
                    if (statusCode == 404 && "DELETE".equalsIgnoreCase(method)) {
                        LOGGER.debug("Replication to follower '{}' for DELETE {} returned HTTP 404 - " +
                                "resource was already absent on follower (idempotent).", follower.getNodeId(), path);
                    } else {
                        LOGGER.warn("Replication to follower '{}' for {} {} returned HTTP {}. " +
                                        "Check follower logs for rejection reason (CSRF/auth/authorization).",
                                follower.getNodeId(), method, path, statusCode);
                    }
                } else {
                    LOGGER.debug("Replicated {} {} to follower '{}': HTTP {}.",
                            method, path, follower.getNodeId(), statusCode);
                }
            }
        } catch (final InterruptedIOException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Interrupted replicating {} {} to follower '{}'.", method, path, follower.getNodeId());
        } catch (final Exception e) {
            LOGGER.error("Failed to replicate {} {} to follower '{}': {}",
                    method, path, follower.getNodeId(), e.getMessage());
        }
    }

    private static String buildUrl(final String baseUrl, final String path, final String queryString) {
        final StringBuilder sb = new StringBuilder(baseUrl);
        if (!path.startsWith("/")) {
            sb.append('/');
        }
        sb.append(path);
        if (queryString != null && !queryString.isEmpty()) {
            sb.append('?').append(queryString);
        }
        return sb.toString();
    }

    private static HttpRequestBase createRequest(final String method, final String url, final byte[] body) {
        if (body != null && body.length > 0) {
            final HttpEntityEnclosingRequestBase request = new HttpEntityEnclosingRequestBase() {
                @Override
                public String getMethod() {
                    return method;
                }
            };
            request.setURI(URI.create(url));
            request.setEntity(new ByteArrayEntity(body));
            return request;
        }

        final HttpRequestBase request = new HttpRequestBase() {
            @Override
            public String getMethod() {
                return method;
            }
        };
        request.setURI(URI.create(url));
        return request;
    }

}
