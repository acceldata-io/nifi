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
package org.apache.nifi.web.api;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import io.swagger.annotations.Authorization;
import org.apache.nifi.authorization.Authorizer;
import org.apache.nifi.authorization.RequestAction;
import org.apache.nifi.authorization.resource.Authorizable;
import org.apache.nifi.authorization.user.NiFiUserUtils;
import org.apache.nifi.cluster.manager.NodeResponse;
import org.apache.nifi.web.NiFiServiceFacade;
import org.apache.nifi.web.api.dto.JmxMetricsResultDTO;
import org.apache.nifi.web.api.dto.SystemDiagnosticsDTO;
import org.apache.nifi.web.api.entity.JmxMetricsResultsEntity;
import org.apache.nifi.web.api.entity.SystemDiagnosticsEntity;
import org.apache.nifi.web.api.metrics.jmx.JmxMetricsService;

import javax.ws.rs.Consumes;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.HttpMethod;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.Collection;

/**
 * RESTful endpoint for retrieving system diagnostics.
 */
@Path("/system-diagnostics")
@Api(
        value = "/system-diagnostics",
        description = "Endpoint for accessing system diagnostics."
)
public class SystemDiagnosticsResource extends ApplicationResource {
    private JmxMetricsService jmxMetricsService;
    private NiFiServiceFacade serviceFacade;
    private Authorizer authorizer;

    private void authorizeSystem() {
        serviceFacade.authorizeAccess(lookup -> {
            final Authorizable system = lookup.getSystem();
            system.authorize(authorizer, RequestAction.READ, NiFiUserUtils.getNiFiUser());
        });
    }

    /**
     * Gets the system diagnostics for this NiFi instance.
     *
     * @return A systemDiagnosticsEntity.
     * @throws InterruptedException if interrupted
     */
    @GET
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            value = "Gets the diagnostics for the system NiFi is running on",
            response = SystemDiagnosticsEntity.class,
            authorizations = {
                    @Authorization(value = "Read - /system")
            }
    )
    @ApiResponses(
            value = {
                    @ApiResponse(code = 401, message = "Client could not be authenticated."),
                    @ApiResponse(code = 403, message = "Client is not authorized to make this request."),}
    )
    public Response getSystemDiagnostics(
            @ApiParam(
                    value = "Whether or not to include the breakdown per node. Optional, defaults to false",
                    required = false
            )
            @QueryParam("nodewise") @DefaultValue(NODEWISE) final Boolean nodewise,
            @ApiParam(
                    value = "The id of the node where to get the status.",
                    required = false
            )
            @QueryParam("clusterNodeId") final String clusterNodeId) throws InterruptedException {

        authorizeSystem();

        // ensure a valid request
        if (Boolean.TRUE.equals(nodewise) && clusterNodeId != null) {
            throw new IllegalArgumentException("Nodewise requests cannot be directed at a specific node.");
        }

        if (isReplicateRequest()) {
            // determine where this request should be sent
            if (clusterNodeId == null) {
                final NodeResponse nodeResponse;

                // Determine whether we should replicate only to the cluster coordinator, or if we should replicate directly
                // to the cluster nodes themselves.
                if (getReplicationTarget() == ReplicationTarget.CLUSTER_NODES) {
                    nodeResponse = getRequestReplicator().replicate(HttpMethod.GET, getAbsolutePath(), getRequestParameters(), getHeaders()).awaitMergedResponse();
                } else {
                    nodeResponse = getRequestReplicator().forwardToCoordinator(
                            getClusterCoordinatorNode(), HttpMethod.GET, getAbsolutePath(), getRequestParameters(), getHeaders()).awaitMergedResponse();
                }

                final SystemDiagnosticsEntity entity = (SystemDiagnosticsEntity) nodeResponse.getUpdatedEntity();

                // ensure there is an updated entity (result of merging) and prune the response as necessary
                if (entity != null && !nodewise) {
                    entity.getSystemDiagnostics().setNodeSnapshots(null);
                }

                return nodeResponse.getResponse();
            } else {
                return replicate(HttpMethod.GET);
            }
        }

        final SystemDiagnosticsDTO systemDiagnosticsDto = serviceFacade.getSystemDiagnostics();

        // create the response
        final SystemDiagnosticsEntity entity = new SystemDiagnosticsEntity();
        entity.setSystemDiagnostics(systemDiagnosticsDto);

        // generate the response
        return generateOkResponse(entity).build();
    }

    /**
     * Retrieves the JMX metrics.
     *
     * @return A jmxMetricsResult list.
     */
    @Path("jmx-metrics")
    @GET
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            value = "Retrieve available JMX metrics",
            notes = NON_GUARANTEED_ENDPOINT,
            response = JmxMetricsResultsEntity.class,
            authorizations = {
                    @Authorization(value = "Read - /system")
            }
    )
    @ApiResponses(
            value = {
                    @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
                    @ApiResponse(code = 401, message = "Client could not be authenticated."),
                    @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
                    @ApiResponse(code = 404, message = "The specified resource could not be found."),
                    @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
            }
    )
    public Response getJmxMetrics(
            @ApiParam(
                    value = "Regular Expression Pattern to be applied against the ObjectName")
            @QueryParam("beanNameFilter") final String beanNameFilter

    ) {
        authorizeJmxMetrics();

        final Collection<JmxMetricsResultDTO> results = jmxMetricsService.getFilteredMBeanMetrics(beanNameFilter);
        final JmxMetricsResultsEntity entity = new JmxMetricsResultsEntity();
        entity.setJmxMetricsResults(results);

        return generateOkResponse(entity)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .build();
    }

    private void authorizeJmxMetrics() {
        serviceFacade.authorizeAccess(lookup -> {
            final Authorizable system = lookup.getSystem();
            system.authorize(authorizer, RequestAction.READ, NiFiUserUtils.getNiFiUser());
        });
    }

    // setters

    public void setServiceFacade(NiFiServiceFacade serviceFacade) {
        this.serviceFacade = serviceFacade;
    }

    public void setAuthorizer(Authorizer authorizer) {
        this.authorizer = authorizer;
    }

    public void setJmxMetricsService(final JmxMetricsService jmxMetricsService) {
        this.jmxMetricsService = jmxMetricsService;
    }
}
