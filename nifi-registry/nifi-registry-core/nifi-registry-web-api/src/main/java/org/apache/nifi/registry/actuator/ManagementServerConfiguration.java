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
package org.apache.nifi.registry.actuator;

import org.apache.nifi.registry.web.security.NiFiRegistrySecurityConfig;
import org.springframework.boot.actuate.autoconfigure.web.ManagementContextConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.SearchStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Provides the embedded servlet container required by the Spring Boot management child context
 * when {@code management.server.port} is set to a different port than the main application.
 *
 * <p>In WAR deployments the main application context runs inside an external container
 * (Jetty). The management child context spins up its own embedded Tomcat on the management
 * port — {@code spring-boot-starter-tomcat} must be on the classpath (not {@code provided})
 * so that Spring Boot's {@code TomcatServletWebServerFactoryAutoConfiguration} can supply
 * the {@code ServletWebServerFactory} automatically.
 *
 * <p>The management port is expected to be bound to {@code 127.0.0.1} via
 * {@code management.server.address=127.0.0.1}, so no authentication is configured here.
 * Only Ambari (or other host-local management tooling) can reach this port.
 */
@ManagementContextConfiguration(proxyBeanMethods = false)
public class ManagementServerConfiguration {

    /**
     * Only active in the management child context (where NiFiRegistrySecurityConfig is not
     * present in the current context). SearchStrategy.CURRENT prevents this condition from
     * traversing the parent context hierarchy, where NiFiRegistrySecurityConfig is visible.
     */
    @Bean
    @ConditionalOnMissingBean(value = NiFiRegistrySecurityConfig.class, search = SearchStrategy.CURRENT)
    public SecurityFilterChain managementSecurityFilterChain(final HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable)
                .build();
    }
}
