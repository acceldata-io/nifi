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
package org.apache.nifi.registry.web.security;

import com.google.common.collect.ImmutableList;
import org.apache.nifi.registry.cluster.NodeRegistry;
import org.apache.nifi.registry.properties.NiFiRegistryProperties;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@link CorsConfigurationSource} that builds the allowed-origins list dynamically
 * from the live cluster membership (via {@link NodeRegistry}) plus any additional
 * origins configured with {@code nifi.registry.cluster.allowed.origins}.
 *
 * <p>This is needed because in an HA cluster the follower node returns a
 * {@code 307 Temporary Redirect} to the leader.  The browser follows the redirect but
 * the redirect target has a different hostname, making the subsequent request
 * cross-origin from the browser's perspective.  Without CORS headers on the leader the
 * browser blocks the request.
 *
 * <p>The CORS configuration is evaluated per-request so it always reflects the current
 * cluster membership, including nodes that joined after startup.
 *
 * <p>When {@code nodeRegistry} is {@code null} (standalone / database-coordination mode)
 * only the statically configured origins are allowed.  If the result is empty, {@code null}
 * is returned so that Spring's CORS processor skips CORS processing entirely (preserving
 * the pre-HA behaviour for standalone deployments).
 */
public class ClusterAwareCorsConfigurationSource implements CorsConfigurationSource {

    private static final List<String> ALLOWED_METHODS =
        ImmutableList.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD");

    private final NodeRegistry nodeRegistry;
    private final List<String> configuredOrigins;

    public ClusterAwareCorsConfigurationSource(final NodeRegistry nodeRegistry,
                                               final NiFiRegistryProperties properties) {
        this.nodeRegistry = nodeRegistry;
        this.configuredOrigins = properties.getClusterAllowedOrigins();
    }

    @Override
    public CorsConfiguration getCorsConfiguration(javax.servlet.http.HttpServletRequest request) {
        // Collect exact base URLs from live cluster members (literal match).
        final List<String> nodeOrigins = new ArrayList<>();
        if (nodeRegistry != null) {
            nodeRegistry.getAllNodes().forEach(node -> nodeOrigins.add(node.getBaseUrl()));
        }

        if (nodeOrigins.isEmpty() && configuredOrigins.isEmpty()) {
            // No cluster, no configured origins — return null to skip CORS processing.
            return null;
        }

        final CorsConfiguration config = new CorsConfiguration();
        // Exact node registry URLs — literal comparison, fastest path.
        if (!nodeOrigins.isEmpty()) {
            config.setAllowedOrigins(nodeOrigins);
        }
        // Configured origins go into allowedOriginPatterns so that glob wildcards
        // like https://*.mycompany.internal:61080 are evaluated as patterns.
        // allowedOriginPatterns also accepts plain exact URLs, so there is no harm
        // mixing both forms here. The two lists are checked additively by Spring.
        if (!configuredOrigins.isEmpty()) {
            config.setAllowedOriginPatterns(configuredOrigins);
        }
        config.setAllowedMethods(ALLOWED_METHODS);
        // Allow all request headers so that Authorization, X-Request-With, Content-Type,
        // and any custom cluster headers pass through the preflight check.
        config.addAllowedHeader("*");
        // Expose the Location header so the Angular client can read the redirect target
        // (useful for debugging, though the browser follows it automatically).
        config.addExposedHeader("Location");
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        return config;
    }
}
