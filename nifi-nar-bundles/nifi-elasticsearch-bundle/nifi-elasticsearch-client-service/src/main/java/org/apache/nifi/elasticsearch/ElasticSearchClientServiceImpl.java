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

package org.apache.nifi.elasticsearch;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import org.apache.commons.io.IOUtils;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.entity.ContentType;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.message.BasicHeader;
import org.apache.http.nio.entity.NStringEntity;
import org.apache.nifi.annotation.behavior.DynamicProperty;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnDisabled;
import org.apache.nifi.annotation.lifecycle.OnEnabled;
import org.apache.nifi.components.ConfigVerificationResult;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.ValidationContext;
import org.apache.nifi.components.ValidationResult;
import org.apache.nifi.controller.AbstractControllerService;
import org.apache.nifi.controller.ConfigurationContext;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.logging.ComponentLog;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.proxy.ProxyConfiguration;
import org.apache.nifi.proxy.ProxyConfigurationService;
import org.apache.nifi.reporting.InitializationException;
import org.apache.nifi.ssl.SSLContextService;
import org.apache.nifi.util.StopWatch;
import org.apache.nifi.util.StringUtils;
import org.elasticsearch.client.Node;
import org.elasticsearch.client.NodeSelector;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.elasticsearch.client.sniff.ElasticsearchNodesSniffer;
import org.elasticsearch.client.sniff.SniffOnFailureListener;
import org.elasticsearch.client.sniff.Sniffer;

import javax.net.ssl.SSLContext;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Tags({"elasticsearch", "elasticsearch6", "elasticsearch7", "elasticsearch8", "client"})
@CapabilityDescription("A controller service for accessing an Elasticsearch client. " +
        "Uses the Elasticsearch REST Client (7.13.4, the last version before client connections verify" +
        "the server is Elastic provided, this should allow for connections to compatible alternatives, e.g. AWS OpenSearch)")
@DynamicProperty(
        name = "The name of a Request Header to add",
        value = "The value of the Header",
        expressionLanguageScope = ExpressionLanguageScope.VARIABLE_REGISTRY,
        description = "Adds the specified property name/value as a Request Header in the Elasticsearch requests.")
public class ElasticSearchClientServiceImpl extends AbstractControllerService implements ElasticSearchClientService {
    public static final String VERIFICATION_STEP_CONNECTION = "Elasticsearch Connection";
    public static final String VERIFICATION_STEP_CLIENT_SETUP = "Elasticsearch Rest Client Setup";
    public static final String VERIFICATION_STEP_WARNINGS = "Elasticsearch Warnings";
    public static final String VERIFICATION_STEP_SNIFFER = "Elasticsearch Sniffer";

    private ObjectMapper mapper;

    private static final List<PropertyDescriptor> properties;

    private RestClient client;

    private Sniffer sniffer;

    private String url;
    private Charset responseCharset;
    private ObjectWriter prettyPrintWriter;

    static {
        final List<PropertyDescriptor> props = new ArrayList<>();
        props.add(HTTP_HOSTS);
        props.add(PATH_PREFIX);
        props.add(AUTHORIZATION_SCHEME);
        props.add(USERNAME);
        props.add(PASSWORD);
        props.add(API_KEY_ID);
        props.add(API_KEY);
        props.add(PROP_SSL_CONTEXT_SERVICE);
        props.add(PROXY_CONFIGURATION_SERVICE);
        props.add(CONNECT_TIMEOUT);
        props.add(SOCKET_TIMEOUT);
        props.add(RETRY_TIMEOUT);
        props.add(CHARSET);
        props.add(SUPPRESS_NULLS);
        props.add(COMPRESSION);
        props.add(SEND_META_HEADER);
        props.add(STRICT_DEPRECATION);
        props.add(NODE_SELECTOR);
        props.add(SNIFF_CLUSTER_NODES);
        props.add(SNIFFER_INTERVAL);
        props.add(SNIFFER_REQUEST_TIMEOUT);
        props.add(SNIFF_ON_FAILURE);
        props.add(SNIFFER_FAILURE_DELAY);

        properties = Collections.unmodifiableList(props);
    }

    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return properties;
    }

    @Override
    protected PropertyDescriptor getSupportedDynamicPropertyDescriptor(final String propertyDescriptorName) {
        return new PropertyDescriptor.Builder()
                .name(propertyDescriptorName)
                .required(false)
                .addValidator(StandardValidators.ATTRIBUTE_EXPRESSION_LANGUAGE_VALIDATOR)
                .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
                .dynamic(true)
                .build();
    }

    @Override
    protected Collection<ValidationResult> customValidate(final ValidationContext validationContext) {
        final List<ValidationResult> results = new ArrayList<>(1);

        final AuthorizationScheme authorizationScheme = AuthorizationScheme.valueOf(validationContext.getProperty(AUTHORIZATION_SCHEME).getValue());

        final boolean usernameSet = validationContext.getProperty(USERNAME).isSet();
        final boolean passwordSet = validationContext.getProperty(PASSWORD).isSet();

        final boolean apiKeyIdSet = validationContext.getProperty(API_KEY_ID).isSet();
        final boolean apiKeySet = validationContext.getProperty(API_KEY).isSet();

        final SSLContextService sslService = validationContext.getProperty(PROP_SSL_CONTEXT_SERVICE).asControllerService(SSLContextService.class);

        if (authorizationScheme == AuthorizationScheme.PKI && (sslService == null || !sslService.isKeyStoreConfigured())) {
            results.add(new ValidationResult.Builder().subject(PROP_SSL_CONTEXT_SERVICE.getName()).valid(false)
                    .explanation(String.format("if '%s' is '%s' then '%s' must be set and specify a Keystore for mutual TLS encryption.",
                            AUTHORIZATION_SCHEME.getDisplayName(), authorizationScheme.getDisplayName(), PROP_SSL_CONTEXT_SERVICE.getDisplayName())
                    ).build()
            );
        }

        if (usernameSet && !passwordSet) {
            addAuthorizationPropertiesValidationIssue(results, USERNAME, PASSWORD);
        } else if (passwordSet && !usernameSet) {
            addAuthorizationPropertiesValidationIssue(results, PASSWORD, USERNAME);
        }

        if (apiKeyIdSet && !apiKeySet) {
            addAuthorizationPropertiesValidationIssue(results, API_KEY_ID, API_KEY);
        } else if (apiKeySet && !apiKeyIdSet) {
            addAuthorizationPropertiesValidationIssue(results, API_KEY, API_KEY_ID);
        }

        final boolean sniffClusterNodes = validationContext.getProperty(SNIFF_CLUSTER_NODES).asBoolean();
        final boolean sniffOnFailure = validationContext.getProperty(SNIFF_ON_FAILURE).asBoolean();
        if (sniffOnFailure && !sniffClusterNodes) {
            results.add(new ValidationResult.Builder().subject(SNIFF_ON_FAILURE.getName()).valid(false)
                    .explanation(String.format("'%s' cannot be enabled if '%s' is disabled", SNIFF_ON_FAILURE.getDisplayName(), SNIFF_CLUSTER_NODES.getDisplayName())).build());
        }

        return results;
    }

    private void addAuthorizationPropertiesValidationIssue(final List<ValidationResult> results, final PropertyDescriptor presentProperty, final PropertyDescriptor missingProperty) {
        results.add(new ValidationResult.Builder().subject(missingProperty.getName()).valid(false)
                .explanation(String.format("if '%s' is then '%s' must be set.", presentProperty.getDisplayName(), missingProperty.getDisplayName()))
                .build()
        );
    }

    @OnEnabled
    public void onEnabled(final ConfigurationContext context) throws InitializationException {
        try {
            this.client = setupClient(context);
            this.sniffer = setupSniffer(context, this.client);
            responseCharset = Charset.forName(context.getProperty(CHARSET).getValue());

            // re-create the ObjectMapper in case the SUPPRESS_NULLS property has changed - the JsonInclude settings aren't dynamic
            mapper = new ObjectMapper();
            if (ALWAYS_SUPPRESS.getValue().equals(context.getProperty(SUPPRESS_NULLS).getValue())) {
                mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
                mapper.setSerializationInclusion(JsonInclude.Include.NON_EMPTY);
            }

            prettyPrintWriter = mapper.writerWithDefaultPrettyPrinter();
        } catch (final Exception ex) {
            getLogger().error("Could not initialize ElasticSearch client.", ex);
            throw new InitializationException(ex);
        }
    }

    @OnDisabled
    public void onDisabled() throws IOException {
        if (this.sniffer != null) {
            this.sniffer.close();
            this.sniffer = null;
        }

        if (this.client != null) {
            this.client.close();
            this.client = null;
        }
        this.url = null;
    }

    @Override
    public List<ConfigVerificationResult> verify(final ConfigurationContext context, final ComponentLog verificationLogger, final Map<String, String> variables) {
        final List<ConfigVerificationResult> results = new ArrayList<>();
        final ConfigVerificationResult.Builder clientSetupResult = new ConfigVerificationResult.Builder()
                .verificationStepName(VERIFICATION_STEP_CLIENT_SETUP);
        final ConfigVerificationResult.Builder connectionResult = new ConfigVerificationResult.Builder()
                .verificationStepName(VERIFICATION_STEP_CONNECTION);
        final ConfigVerificationResult.Builder warningsResult = new ConfigVerificationResult.Builder()
                .verificationStepName(VERIFICATION_STEP_WARNINGS);
        final ConfigVerificationResult.Builder snifferResult = new ConfigVerificationResult.Builder()
                .verificationStepName(VERIFICATION_STEP_SNIFFER);

        // configure the Rest Client
        try (final RestClient verifyClient = setupClient(context)) {
            clientSetupResult.outcome(ConfigVerificationResult.Outcome.SUCCESSFUL);

            // try to fetch the Elasticsearch root endpoint (system summary)
            verifyRootConnection(verifyClient, connectionResult, warningsResult);

            // try sniffing for cluster nodes
            verifySniffer(context, verifyClient, snifferResult);
        } catch (final MalformedURLException mue) {
            clientSetupResult.outcome(ConfigVerificationResult.Outcome.FAILED)
                    .explanation("Incorrect/invalid " + ElasticSearchClientService.HTTP_HOSTS.getDisplayName());
        } catch (final InitializationException ie) {
            clientSetupResult.outcome(ConfigVerificationResult.Outcome.FAILED)
                    .explanation("Incorrect/invalid " + ElasticSearchClientService.PROP_SSL_CONTEXT_SERVICE.getDisplayName());
        } catch (final Exception ex) {
            getLogger().warn("Unable to setup Elasticsearch Rest Client", ex);

            clientSetupResult.outcome(ConfigVerificationResult.Outcome.FAILED)
                    .explanation("Unable to configure, are all mandatory properties set (see logs for details)?");
        } finally {
            final ConfigVerificationResult clientSetup = clientSetupResult.build();
            if (ConfigVerificationResult.Outcome.SUCCESSFUL != clientSetup.getOutcome()) {
                connectionResult.outcome(ConfigVerificationResult.Outcome.SKIPPED)
                        .explanation("Elasticsearch Rest Client not configured");
                warningsResult.outcome(ConfigVerificationResult.Outcome.SKIPPED)
                        .explanation("Elasticsearch Rest Client not configured");
                snifferResult.outcome(ConfigVerificationResult.Outcome.SKIPPED)
                        .explanation("Elasticsearch Rest Client not configured");
            }

            results.add(clientSetup);
            results.add(connectionResult.build());
            results.add(warningsResult.build());
            results.add(snifferResult.build());
        }

        return results;
    }

    private void verifySniffer(final ConfigurationContext context, final RestClient verifyClient, final ConfigVerificationResult.Builder snifferResult) {
        try (final Sniffer verifySniffer = setupSniffer(context, verifyClient)) {
            if (verifySniffer != null) {
                final List<Node> originalNodes = verifyClient.getNodes();
                // cannot access the NodesSniffer from the parent Sniffer, so set up a second instance here
                final ElasticsearchNodesSniffer elasticsearchNodesSniffer = setupElasticsearchNodesSniffer(context, verifyClient);
                final List<Node> nodes = elasticsearchNodesSniffer.sniff();

                // attempt to connect to each Elasticsearch Node using the RestClient
                final AtomicInteger successfulInstances = new AtomicInteger(0);
                final AtomicInteger warningInstances = new AtomicInteger(0);
                nodes.forEach(n -> {
                    try {
                        verifyClient.setNodes(Collections.singletonList(n));
                        final List<String> warnings = getElasticsearchRoot(verifyClient);
                        successfulInstances.getAndIncrement();
                        if (!warnings.isEmpty()) {
                            warningInstances.getAndIncrement();
                        }
                    } catch (final Exception ex) {
                        getLogger().warn("Elasticsearch Node {} connection failed", n.getHost().toURI(), ex);
                    }
                });
                // reset Nodes list on RestClient to pre-Sniffer state (match user's Verify settings)
                verifyClient.setNodes(originalNodes);

                if (successfulInstances.get() < nodes.size()) {
                    snifferResult.outcome(ConfigVerificationResult.Outcome.FAILED).explanation(
                            String.format("Sniffing for Elasticsearch cluster nodes found %d nodes but %d could not be contacted (%d with warnings during connection tests)",
                                    nodes.size(), nodes.size() - successfulInstances.get(), warningInstances.get())
                    );
                } else {
                    snifferResult.outcome(ConfigVerificationResult.Outcome.SUCCESSFUL).explanation(
                            String.format("Sniffing for Elasticsearch cluster nodes found %d nodes (%d with warnings during connection tests)",
                                    nodes.size(), warningInstances.get())
                    );
                }
            } else {
                snifferResult.outcome(ConfigVerificationResult.Outcome.SKIPPED).explanation("Sniff on Connection not enabled");
            }
        } catch (final Exception ex) {
            getLogger().warn("Unable to sniff for Elasticsearch cluster nodes", ex);

            snifferResult.outcome(ConfigVerificationResult.Outcome.FAILED)
                    .explanation("Sniffing for Elasticsearch cluster nodes failed");
        }
    }

    private List<String> getElasticsearchRoot(final RestClient verifyClient) throws IOException {
        final Response response = verifyClient.performRequest(new Request("GET", "/"));
        final List<String> warnings = parseResponseWarningHeaders(response);
        parseResponse(response);

        return warnings;
    }

    private void verifyRootConnection(final RestClient verifyClient, final ConfigVerificationResult.Builder connectionResult, final ConfigVerificationResult.Builder warningsResult) {
        try {
            final List<String> warnings = getElasticsearchRoot(verifyClient);

            connectionResult.outcome(ConfigVerificationResult.Outcome.SUCCESSFUL);
            if (warnings.isEmpty()) {
                warningsResult.outcome(ConfigVerificationResult.Outcome.SUCCESSFUL);
            } else {
                warningsResult.outcome(ConfigVerificationResult.Outcome.FAILED)
                        .explanation("Elasticsearch Warnings received during request (see logs for details)");
            }
        } catch (ElasticsearchException | IOException ex) {
            getLogger().warn("Unable to connect to Elasticsearch", ex);

            connectionResult.outcome(ConfigVerificationResult.Outcome.FAILED)
                    .explanation("Unable to retrieve system summary from Elasticsearch root endpoint");
            warningsResult.outcome(ConfigVerificationResult.Outcome.SKIPPED)
                    .explanation("Connection to Elasticsearch failed");
        }
    }

    private RestClient setupClient(final ConfigurationContext context) throws MalformedURLException, InitializationException {
        final Integer connectTimeout = context.getProperty(CONNECT_TIMEOUT).asInteger();
        final Integer socketTimeout = context.getProperty(SOCKET_TIMEOUT).asInteger();

        final NodeSelector nodeSelector = NODE_SELECTOR_ANY.getValue().equals(context.getProperty(NODE_SELECTOR).getValue())
                ? NodeSelector.ANY
                : NodeSelector.SKIP_DEDICATED_MASTERS;
        final String pathPrefix = context.getProperty(PATH_PREFIX).getValue();

        final boolean compress = context.getProperty(COMPRESSION).asBoolean();
        final boolean sendMetaHeader = context.getProperty(SEND_META_HEADER).asBoolean();
        final boolean strictDeprecation = context.getProperty(STRICT_DEPRECATION).asBoolean();
        final boolean sniffOnFailure = context.getProperty(SNIFF_ON_FAILURE).asBoolean();

        final RestClientBuilder builder = RestClient.builder(getHttpHosts(context));
        addAuthAndProxy(context, builder)
                .setRequestConfigCallback(requestConfigBuilder -> {
                    requestConfigBuilder.setConnectTimeout(connectTimeout);
                    requestConfigBuilder.setSocketTimeout(socketTimeout);
                    return requestConfigBuilder;
                })
                .setCompressionEnabled(compress)
                .setMetaHeaderEnabled(sendMetaHeader)
                .setStrictDeprecationMode(strictDeprecation)
                .setNodeSelector(nodeSelector);

        if (sniffOnFailure && sniffer != null) {
            final SniffOnFailureListener sniffOnFailureListener = new SniffOnFailureListener();
            sniffOnFailureListener.setSniffer(sniffer);
            builder.setFailureListener(sniffOnFailureListener);
        }

        if (StringUtils.isNotBlank(pathPrefix)) {
            builder.setPathPrefix(pathPrefix);
        }

        return builder.build();
    }

    private HttpHost[] getHttpHosts(final ConfigurationContext context) throws MalformedURLException {
        final String hosts = context.getProperty(HTTP_HOSTS).evaluateAttributeExpressions().getValue();

        final List<String> hostsSplit = Arrays.stream(hosts.split(",\\s*")).map(String::trim).collect(Collectors.toList());
        this.url = hostsSplit.get(0);
        final List<HttpHost> hh = new ArrayList<>(hostsSplit.size());
        for (final String host : hostsSplit) {
            final URL u = URI.create(host).toURL();
            hh.add(new HttpHost(u.getHost(), u.getPort(), u.getProtocol()));
        }

        return hh.toArray(new HttpHost[0]);
    }

    private RestClientBuilder addAuthAndProxy(final ConfigurationContext context, final RestClientBuilder builder) throws InitializationException {
        final AuthorizationScheme authorizationScheme = AuthorizationScheme.valueOf(context.getProperty(AUTHORIZATION_SCHEME).getValue());

        final String username = context.getProperty(USERNAME).evaluateAttributeExpressions().getValue();
        final String password = context.getProperty(PASSWORD).evaluateAttributeExpressions().getValue();

        final String apiKeyId = context.getProperty(API_KEY_ID).getValue();
        final String apiKey = context.getProperty(API_KEY).getValue();

        final SSLContext sslContext = getSSLContext(context);
        final ProxyConfigurationService proxyConfigurationService = context.getProperty(PROXY_CONFIGURATION_SERVICE).asControllerService(ProxyConfigurationService.class);

        return builder.setHttpClientConfigCallback(httpClientBuilder -> {
            if (sslContext != null) {
                httpClientBuilder.setSSLContext(sslContext);
            }

            CredentialsProvider credentialsProvider = null;
            if (AuthorizationScheme.BASIC == authorizationScheme && username != null && password != null) {
                credentialsProvider = addBasicAuthCredentials(null, AuthScope.ANY, username, password);
            }

            final List<Header> defaultHeaders = getDefaultHeadersFromDynamicProperties(context);
            if (AuthorizationScheme.API_KEY == authorizationScheme && apiKeyId != null && apiKey != null) {
                defaultHeaders.add(createApiKeyAuthorizationHeader(apiKeyId, apiKey));
            }
            if (!defaultHeaders.isEmpty()) {
                builder.setDefaultHeaders(defaultHeaders.toArray(new Header[0]));
            }

            if (proxyConfigurationService != null) {
                final ProxyConfiguration proxyConfiguration = proxyConfigurationService.getConfiguration();
                if (Proxy.Type.HTTP == proxyConfiguration.getProxyType()) {
                    final HttpHost proxy = new HttpHost(proxyConfiguration.getProxyServerHost(), proxyConfiguration.getProxyServerPort(), "http");
                    httpClientBuilder.setProxy(proxy);

                    credentialsProvider = addBasicAuthCredentials(credentialsProvider, new AuthScope(proxy), proxyConfiguration.getProxyUserName(), proxyConfiguration.getProxyUserPassword());
                }
            }

            if (credentialsProvider != null) {
                httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider);
            }

            return httpClientBuilder;
        });
    }

    private SSLContext getSSLContext(final ConfigurationContext context) throws InitializationException {
        final SSLContextService sslService =
                context.getProperty(PROP_SSL_CONTEXT_SERVICE).asControllerService(SSLContextService.class);

        try {
            return (sslService != null && (sslService.isKeyStoreConfigured() || sslService.isTrustStoreConfigured()))
                    ? sslService.createContext() : null;
        } catch (final Exception e) {
            getLogger().error("Error building up SSL Context from the supplied configuration.", e);
            throw new InitializationException(e);
        }
    }

    private CredentialsProvider addBasicAuthCredentials(final CredentialsProvider credentialsProvider, final AuthScope authScope,
                                                        final String username, final String password) {
        final CredentialsProvider cp = credentialsProvider != null ? credentialsProvider : new BasicCredentialsProvider();

        if (StringUtils.isNotBlank(username) && StringUtils.isNotBlank(password)) {
            cp.setCredentials(
                    authScope == null ? AuthScope.ANY : authScope,
                    new UsernamePasswordCredentials(username, password)
            );
        }

        return cp;
    }

    private List<Header> getDefaultHeadersFromDynamicProperties(final ConfigurationContext context) {
        return context.getProperties().entrySet().stream()
                // filter non-null dynamic properties
                .filter(e -> e.getKey().isDynamic() && StringUtils.isNotBlank(e.getValue())
                        && StringUtils.isNotBlank(context.getProperty(e.getKey()).evaluateAttributeExpressions().getValue())
                )
                // convert to Headers
                .map(e -> new BasicHeader(e.getKey().getName(),
                        context.getProperty(e.getKey()).evaluateAttributeExpressions().getValue()))
                .collect(Collectors.toList());
    }

    private BasicHeader createApiKeyAuthorizationHeader(final String apiKeyId, final String apiKey) {
        final String apiKeyCredentials = String.format("%s:%s", apiKeyId, apiKey);
        final String apiKeyAuth = Base64.getEncoder().encodeToString((apiKeyCredentials).getBytes(StandardCharsets.UTF_8));
        return new BasicHeader("Authorization", "ApiKey " + apiKeyAuth);
    }

    private Sniffer setupSniffer(final ConfigurationContext context, final RestClient restClient) {
        final boolean sniffClusterNodes = context.getProperty(SNIFF_CLUSTER_NODES).asBoolean();
        final int snifferIntervalMillis = context.getProperty(SNIFFER_INTERVAL).asTimePeriod(TimeUnit.MILLISECONDS).intValue();
        final int snifferFailureDelayMillis = context.getProperty(SNIFFER_FAILURE_DELAY).asTimePeriod(TimeUnit.MILLISECONDS).intValue();

        if (sniffClusterNodes) {
            return Sniffer.builder(restClient)
                    .setSniffIntervalMillis(snifferIntervalMillis)
                    .setSniffAfterFailureDelayMillis(snifferFailureDelayMillis)
                    .setNodesSniffer(setupElasticsearchNodesSniffer(context, restClient))
                    .build();
        }

        return null;
    }

    private ElasticsearchNodesSniffer setupElasticsearchNodesSniffer(final ConfigurationContext context, final RestClient restClient) {
        final Long snifferRequestTimeoutMillis = context.getProperty(SNIFFER_REQUEST_TIMEOUT).asTimePeriod(TimeUnit.MILLISECONDS);
        final ElasticsearchNodesSniffer.Scheme scheme = this.url.toLowerCase(Locale.getDefault()).startsWith("https://")
                ? ElasticsearchNodesSniffer.Scheme.HTTPS : ElasticsearchNodesSniffer.Scheme.HTTP;

        return new ElasticsearchNodesSniffer(restClient, snifferRequestTimeoutMillis, scheme);
    }

    private void appendIndex(final StringBuilder sb, final String index) {
        if (StringUtils.isNotBlank(index) && !"/".equals(index)) {
            if (!index.startsWith("/")) {
                sb.append("/");
            }
            sb.append(index);
        }
    }

    private Response runQuery(final String endpoint, final String query, final String index, final String type, final Map<String, String> requestParameters) {
        final StringBuilder sb = new StringBuilder();
        appendIndex(sb, index);
        if (StringUtils.isNotBlank(type)) {
            sb.append("/").append(type);
        }
        sb.append("/").append(endpoint);

        try {
            final HttpEntity queryEntity = new NStringEntity(query, ContentType.APPLICATION_JSON);
            return performRequest("POST", sb.toString(), requestParameters, queryEntity);
        } catch (final Exception e) {
            throw new ElasticsearchException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseResponse(final Response response) {
        final int code = response.getStatusLine().getStatusCode();

        try {
            if (code >= 200 && code < 300) {
                final InputStream inputStream = response.getEntity().getContent();
                final byte[] result = IOUtils.toByteArray(inputStream);
                inputStream.close();
                return mapper.readValue(new String(result, responseCharset), Map.class);
            } else {
                final String errorMessage = String.format("ElasticSearch reported an error while trying to run the query: %s",
                        response.getStatusLine().getReasonPhrase());
                throw new IOException(errorMessage);
            }
        } catch (final Exception ex) {
            throw new ElasticsearchException(ex);
        }
    }

    private List<String> parseResponseWarningHeaders(final Response response) {
        final List<String> warnings = Arrays.stream(response.getHeaders())
                .filter(h -> "Warning".equalsIgnoreCase(h.getName()))
                .map(Header::getValue)
                .collect(Collectors.toList());

        warnings.forEach(w -> getLogger().warn("Elasticsearch Warning: {}", w));

        return warnings;
    }

    @Override
    public IndexOperationResponse add(final IndexOperationRequest operation, final Map<String, String> requestParameters) {
        return bulk(Collections.singletonList(operation), requestParameters);
    }

    private String flatten(final String str) {
        return str.replaceAll("[\\n\\r]", "\\\\n");
    }

    private String buildBulkHeader(final IndexOperationRequest request) throws JsonProcessingException {
        final String operation = request.getOperation().equals(IndexOperationRequest.Operation.Upsert)
                ? "update"
                : request.getOperation().getValue();
        return buildBulkHeader(operation, request.getIndex(), request.getType(), request.getId(), request.getDynamicTemplates(), request.getHeaderFields());
    }

    private String buildBulkHeader(final String operation, final String index, final String type, final String id,
                                   final Map<String, Object> dynamicTemplates, final Map<String, String> headerFields) throws JsonProcessingException {
        final Map<String, Object> operationBody = new HashMap<>();
        operationBody.put("_index", index);
        if (StringUtils.isNotBlank(id)) {
            operationBody.put("_id", id);
        }
        if (StringUtils.isNotBlank(type)) {
            operationBody.put("_type", type);
        }
        if (dynamicTemplates != null && !dynamicTemplates.isEmpty()) {
            operationBody.put("dynamic_templates", dynamicTemplates);
        }
        if (headerFields != null && !headerFields.isEmpty()) {
            headerFields.entrySet().stream().filter(e -> StringUtils.isNotBlank(e.getValue()))
                    .forEach(e -> operationBody.putIfAbsent(e.getKey(), e.getValue()));
        }

        return flatten(mapper.writeValueAsString(Collections.singletonMap(operation, operationBody)));
    }

    protected void buildRequest(final IndexOperationRequest request, final StringBuilder builder) throws JsonProcessingException {
        final String header = buildBulkHeader(request);
        builder.append(header).append("\n");
        switch (request.getOperation()) {
            case Index:
            case Create:
                final String indexDocument = mapper.writeValueAsString(request.getFields());
                builder.append(indexDocument).append("\n");
                break;
            case Update:
            case Upsert:
                final Map<String, Object> updateBody = new HashMap<>(2, 1);
                if (request.getScript() != null && !request.getScript().isEmpty()) {
                    updateBody.put("script", request.getScript());
                    if (request.getOperation().equals(IndexOperationRequest.Operation.Upsert)) {
                        updateBody.put("scripted_upsert", request.isScriptedUpsert());
                        updateBody.put("upsert", request.getFields());
                    }
                } else {
                    updateBody.put("doc", request.getFields());
                    if (request.getOperation().equals(IndexOperationRequest.Operation.Upsert)) {
                        updateBody.put("doc_as_upsert", true);
                    }
                }

                final String update = flatten(mapper.writeValueAsString(updateBody)).trim();
                builder.append(update).append("\n");
                break;
            case Delete:
                // nothing to do for Delete operations, it just needs the header
                break;
            default:
                throw new IllegalArgumentException(String.format("Unhandled Index Operation type: %s", request.getOperation().name()));
        }
    }

    @Override
    public IndexOperationResponse bulk(final List<IndexOperationRequest> operations, final Map<String, String> requestParameters) {
        try {
            final StringBuilder payload = new StringBuilder();
            for (final IndexOperationRequest or : operations) {
                buildRequest(or, payload);
            }

            if (getLogger().isDebugEnabled()) {
                getLogger().debug(payload.toString());
            }
            final HttpEntity entity = new NStringEntity(payload.toString(), ContentType.APPLICATION_JSON);
            final StopWatch watch = new StopWatch();
            watch.start();
            final Response response = performRequest("POST", "/_bulk", requestParameters, entity);
            watch.stop();

            final String rawResponse = IOUtils.toString(response.getEntity().getContent(), StandardCharsets.UTF_8);
            parseResponseWarningHeaders(response);

            if (getLogger().isDebugEnabled()) {
                getLogger().debug(String.format("Response was: %s", rawResponse));
            }

            return IndexOperationResponse.fromJsonResponse(rawResponse);
        } catch (final Exception ex) {
            throw new ElasticsearchException(ex);
        }
    }

    @Override
    public Long count(final String query, final String index, final String type, final Map<String, String> requestParameters) {
        final Response response = runQuery("_count", query, index, type, requestParameters);
        final Map<String, Object> parsed = parseResponse(response);

        return ((Integer)parsed.get("count")).longValue();
    }

    @Override
    public DeleteOperationResponse deleteById(final String index, final String type, final String id, final Map<String, String> requestParameters) {
        return deleteById(index, type, Collections.singletonList(id), requestParameters);
    }

    @Override
    public DeleteOperationResponse deleteById(final String index, final String type, final List<String> ids, final Map<String, String> requestParameters) {
        try {
            final StringBuilder sb = new StringBuilder();
            for (final String id : ids) {
                final String header = buildBulkHeader("delete", index, type, id, null, null);
                sb.append(header).append("\n");
            }
            final HttpEntity entity = new NStringEntity(sb.toString(), ContentType.APPLICATION_JSON);
            final StopWatch watch = new StopWatch();
            watch.start();
            final Response response = performRequest("POST", "/_bulk", requestParameters, entity);
            watch.stop();

            if (getLogger().isDebugEnabled()) {
                getLogger().debug(String.format("Response for bulk delete: %s",
                        IOUtils.toString(response.getEntity().getContent(), StandardCharsets.UTF_8)));
            }

            parseResponseWarningHeaders(response);
            return new DeleteOperationResponse(watch.getDuration(TimeUnit.MILLISECONDS));
        } catch (final Exception ex) {
            throw new ElasticsearchException(ex);
        }
    }

    @Override
    public DeleteOperationResponse deleteByQuery(final String query, final String index, final String type, final Map<String, String> requestParameters) {
        final StopWatch watch = new StopWatch();
        watch.start();
        final Response response = runQuery("_delete_by_query", query, index, type, requestParameters);
        watch.stop();

        // check for errors in response
        parseResponse(response);
        parseResponseWarningHeaders(response);

        return new DeleteOperationResponse(watch.getDuration(TimeUnit.MILLISECONDS));
    }

    @Override
    public UpdateOperationResponse updateByQuery(final String query, final String index, final String type, final Map<String, String> requestParameters) {
        final long start = System.currentTimeMillis();
        final Response response = runQuery("_update_by_query", query, index, type, requestParameters);
        final long end = System.currentTimeMillis();

        // check for errors in response
        parseResponse(response);

        return new UpdateOperationResponse(end - start);
    }

    @Override
    public void refresh(final String index, final Map<String, String> requestParameters) {
        try {
            final StringBuilder endpoint = new StringBuilder();
            appendIndex(endpoint, index);
            endpoint.append("/_refresh");
            final Response response = performRequest("POST", endpoint.toString(), requestParameters, null);
            parseResponseWarningHeaders(response);
        } catch (final Exception ex) {
            throw new ElasticsearchException(ex);
        }
    }

    @Override
    public boolean exists(final String index, final Map<String, String> requestParameters) {
        try {
            final StringBuilder endpoint = new StringBuilder();
            appendIndex(endpoint, index);
            final Response response = performRequest("HEAD", endpoint.toString(), requestParameters, null);
            parseResponseWarningHeaders(response);

            if (response.getStatusLine().getStatusCode() == 200) {
                return true;
            } else if (response.getStatusLine().getStatusCode() == 404) {
                return false;
            } else {
                throw new ProcessException(String.format("Error checking for index existence: %d; %s",
                        response.getStatusLine().getStatusCode(), response.getStatusLine().getReasonPhrase())
                );
            }
        } catch (final Exception ex) {
            throw new ElasticsearchException(ex);
        }
    }

    @Override
    public boolean documentExists(final String index, final String type, final String id, final Map<String, String> requestParameters) {
        boolean exists = true;
        try {
            final Map<String, String> existsParameters = requestParameters != null ? new HashMap<>(requestParameters) : new HashMap<>();
            existsParameters.putIfAbsent("_source", "false");
            get(index, type, id, existsParameters);
        } catch (final ElasticsearchException ee) {
            if (ee.isNotFound()) {
                exists = false;
            } else {
                throw ee;
            }
        }
        return exists;
    }

    @SuppressWarnings("unchecked")
    @Override
    public Map<String, Object> get(final String index, final String type, final String id, final Map<String, String> requestParameters) {
        try {
            final StringBuilder endpoint = new StringBuilder();
            appendIndex(endpoint, index);
            if (StringUtils.isNotBlank(type)) {
                endpoint.append("/").append(type);
            } else {
                endpoint.append("/_doc");
            }
            endpoint.append("/").append(id);

            final Response response = performRequest("GET", endpoint.toString(), requestParameters, null);
            final String body = IOUtils.toString(response.getEntity().getContent(), StandardCharsets.UTF_8);
            parseResponseWarningHeaders(response);

            return (Map<String, Object>) mapper.readValue(body, Map.class).getOrDefault("_source", Collections.emptyMap());
        } catch (final Exception ex) {
            throw new ElasticsearchException(ex);
        }
    }

    /*
     * In pre-7.X ElasticSearch, it should return just a number. 7.X and after they are returning a map.
     */
    @SuppressWarnings("unchecked")
    private int handleSearchCount(final Object raw) {
        if (raw instanceof Number) {
            return Integer.parseInt(raw.toString());
        } else if (raw instanceof Map) {
            return (Integer)((Map<String, Object>)raw).get("value");
        } else {
            throw new ProcessException("Unknown type for hit count.");
        }
    }

    @Override
    public SearchResponse search(final String query, final String index, final String type, final Map<String, String> requestParameters) {
        try {
            final Response response = runQuery("_search", query, index, type, requestParameters);
            return buildSearchResponse(response);
        } catch (final Exception ex) {
            throw new ElasticsearchException(ex);
        }
    }

    @Override
    public SearchResponse scroll(final String scroll) {
        try {
            final HttpEntity scrollEntity = new NStringEntity(scroll, ContentType.APPLICATION_JSON);
            final Response response = performRequest("POST", "/_search/scroll", Collections.emptyMap(), scrollEntity);
            return buildSearchResponse(response);
        } catch (final Exception ex) {
            throw new ElasticsearchException(ex);
        }
    }

    @Override
    public String initialisePointInTime(final String index, final String keepAlive) {
        try {
            final Map<String, String> params = new HashMap<String, String>() {{
                if (StringUtils.isNotBlank(keepAlive)) {
                    put("keep_alive", keepAlive);
                }
            }};
            final StringBuilder endpoint = new StringBuilder();
            appendIndex(endpoint, index);
            endpoint.append("/_pit");
            final Response response = performRequest("POST", endpoint.toString(), params, null);
            final String body = IOUtils.toString(response.getEntity().getContent(), StandardCharsets.UTF_8);
            parseResponseWarningHeaders(response);

            if (getLogger().isDebugEnabled()) {
                getLogger().debug(String.format("Response for initialising Point in Time: %s", body));
            }

            return (String) mapper.readValue(body, Map.class).get("id");
        } catch (final Exception ex) {
            throw new ElasticsearchException(ex);
        }
    }

    @Override
    public DeleteOperationResponse deletePointInTime(final String pitId) {
        try {
            final HttpEntity pitEntity = new NStringEntity(String.format("{\"id\": \"%s\"}", pitId), ContentType.APPLICATION_JSON);

            final StopWatch watch = new StopWatch(true);
            final Response response = performRequest("DELETE", "/_pit", Collections.emptyMap(), pitEntity);
            watch.stop();

            if (getLogger().isDebugEnabled()) {
                getLogger().debug(String.format("Response for deleting Point in Time: %s",
                        IOUtils.toString(response.getEntity().getContent(), StandardCharsets.UTF_8))
                );
            }

            parseResponseWarningHeaders(response);
            return new DeleteOperationResponse(watch.getDuration(TimeUnit.MILLISECONDS));
        } catch (final ResponseException re) {
            if (404 == re.getResponse().getStatusLine().getStatusCode()) {
                getLogger().debug("Point in Time {} not found in Elasticsearch for deletion, ignoring", pitId);
                return new DeleteOperationResponse(0);
            }
            throw new ElasticsearchException(re);
        } catch (final Exception ex) {
            throw new ElasticsearchException(ex);
        }
    }

    @Override
    public DeleteOperationResponse deleteScroll(final String scrollId) {
        try {
            final HttpEntity scrollBody = new NStringEntity(String.format("{\"scroll_id\": \"%s\"}", scrollId), ContentType.APPLICATION_JSON);

            final StopWatch watch = new StopWatch(true);
            final Response response = performRequest("DELETE", "/_search/scroll", Collections.emptyMap(), scrollBody);
            watch.stop();

            if (getLogger().isDebugEnabled()) {
                getLogger().debug(String.format("Response for deleting Scroll: %s",
                        IOUtils.toString(response.getEntity().getContent(), StandardCharsets.UTF_8))
                );
            }

            parseResponseWarningHeaders(response);
            return new DeleteOperationResponse(watch.getDuration(TimeUnit.MILLISECONDS));
        } catch (final ResponseException re) {
            if (404 == re.getResponse().getStatusLine().getStatusCode()) {
                getLogger().debug("Scroll Id {} not found in Elasticsearch for deletion, ignoring", scrollId);
                return new DeleteOperationResponse(0);
            }
            throw new ElasticsearchException(re);
        } catch (final Exception ex) {
            throw new ElasticsearchException(ex);
        }
    }

    @SuppressWarnings("unchecked")
    private SearchResponse buildSearchResponse(final Response response) throws JsonProcessingException {
        final Map<String, Object> parsed = parseResponse(response);
        final List<String> warnings = parseResponseWarningHeaders(response);

        final int took = (Integer)parsed.get("took");
        final boolean timedOut = (Boolean)parsed.get("timed_out");
        final String pitId = parsed.get("pit_id") != null ? (String)parsed.get("pit_id") : null;
        final String scrollId = parsed.get("_scroll_id") != null ? (String)parsed.get("_scroll_id") : null;
        final Map<String, Object> aggregations = parsed.get("aggregations") != null
                ? (Map<String, Object>)parsed.get("aggregations") : new HashMap<>();
        final Map<String, Object> hitsParent = (Map<String, Object>)parsed.get("hits");
        final int count = handleSearchCount(hitsParent.get("total"));
        final List<Map<String, Object>> hits = (List<Map<String, Object>>)hitsParent.get("hits");
        final String searchAfter = getSearchAfter(hits);

        final SearchResponse esr = new SearchResponse(hits, aggregations, pitId, scrollId, searchAfter, count, took, timedOut, warnings);

        if (getLogger().isDebugEnabled()) {
            final String searchSummary = "******************" +
                    String.format(Locale.getDefault(), "Took: %d", took) +
                    String.format(Locale.getDefault(), "Timed out: %s", timedOut) +
                    String.format(Locale.getDefault(), "Aggregation count: %d", aggregations.size()) +
                    String.format(Locale.getDefault(), "Hit count: %d", hits.size()) +
                    String.format(Locale.getDefault(), "PIT Id: %s", pitId) +
                    String.format(Locale.getDefault(), "Scroll Id: %s", scrollId) +
                    String.format(Locale.getDefault(), "Search After: %s", searchAfter) +
                    String.format(Locale.getDefault(), "Total found: %d", count) +
                    String.format(Locale.getDefault(), "Warnings: %s", warnings) +
                    "******************";
            getLogger().debug(searchSummary);
        }

        return esr;
    }

    private String getSearchAfter(final List<Map<String, Object>> hits) throws JsonProcessingException {
        String searchAfter = null;
        if (!hits.isEmpty()) {
            final Object lastHitSort = hits.get(hits.size() - 1).get("sort");
            if (lastHitSort != null && !"null".equalsIgnoreCase(lastHitSort.toString())) {
                searchAfter = mapper.writeValueAsString(lastHitSort);
            }
        }
        return searchAfter;
    }

    @Override
    public String getTransitUrl(final String index, final String type) {
        final StringBuilder transitUrl = new StringBuilder();
        transitUrl.append(this.url);
        appendIndex(transitUrl, index);
        if (StringUtils.isNotBlank(type)) {
            transitUrl.append("/").append(type);
        }
        return transitUrl.toString();
    }

    private Response performRequest(final String method, final String endpoint, final Map<String, String> parameters, final HttpEntity entity) throws IOException {
        final Request request = new Request(method, endpoint);
        if (parameters != null && !parameters.isEmpty()) {
            request.addParameters(parameters);
        }
        if (entity != null) {
            request.setEntity(entity);

            if (getLogger().isDebugEnabled()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                entity.writeTo(out);
                out.close();

                String builder = "Dumping Elasticsearch REST request...\n" +
                        "HTTP Method: " +
                        method +
                        "\n" +
                        "Endpoint: " +
                        endpoint +
                        "\n" +
                        "Parameters: " +
                        prettyPrintWriter.writeValueAsString(parameters) +
                        "\n" +
                        "Request body: " +
                        out +
                        "\n";

                getLogger().debug(builder);
            }
        }

        return client.performRequest(request);
    }
}
