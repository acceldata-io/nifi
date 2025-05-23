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
package org.apache.nifi.dbcp.hive;

import org.apache.commons.dbcp2.BasicDataSource;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hive.jdbc.HiveDriver;
import org.apache.nifi.annotation.behavior.RequiresInstanceClassLoading;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.DeprecationNotice;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnDisabled;
import org.apache.nifi.annotation.lifecycle.OnEnabled;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.PropertyValue;
import org.apache.nifi.components.ValidationContext;
import org.apache.nifi.components.ValidationResult;
import org.apache.nifi.components.resource.ResourceCardinality;
import org.apache.nifi.components.resource.ResourceType;
import org.apache.nifi.controller.AbstractControllerService;
import org.apache.nifi.controller.ConfigurationContext;
import org.apache.nifi.controller.ControllerServiceInitializationContext;
import org.apache.nifi.dbcp.DBCPValidator;
import org.apache.nifi.expression.AttributeExpression;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.hadoop.SecurityUtil;
import org.apache.nifi.kerberos.KerberosCredentialsService;
import org.apache.nifi.logging.ComponentLog;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.reporting.InitializationException;
import org.apache.nifi.security.krb.KerberosKeytabUser;
import org.apache.nifi.security.krb.KerberosLoginException;
import org.apache.nifi.security.krb.KerberosPasswordUser;
import org.apache.nifi.security.krb.KerberosUser;
import org.apache.nifi.util.hive.AuthenticationFailedException;
import org.apache.nifi.util.hive.HiveConfigurator;
import org.apache.nifi.util.hive.ValidationResources;

import java.io.IOException;
import java.lang.reflect.UndeclaredThrowableException;
import java.security.PrivilegedExceptionAction;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Implementation for Database Connection Pooling Service used for Apache Hive 1.1
 * connections. Apache DBCP is used for connection pooling functionality.
 */
@RequiresInstanceClassLoading
@Tags({"hive", "dbcp", "jdbc", "database", "connection", "pooling", "store"})
@CapabilityDescription("Provides Database Connection Pooling Service for Apache Hive 1.1.x. Connections can be asked from pool and returned after usage.")
@DeprecationNotice(classNames = "org.apache.nifi.dbcp.hive.Hive3ConnectionPool")
public class Hive_1_1ConnectionPool extends AbstractControllerService implements Hive_1_1DBCPService {

    private static final String DEFAULT_MAX_CONN_LIFETIME = "-1";

    public static final PropertyDescriptor DATABASE_URL = new PropertyDescriptor.Builder()
            .name("hive-db-connect-url")
            .displayName("Database Connection URL")
            .description("A database connection URL used to connect to a database. May contain database system name, host, port, database name and some parameters."
                    + " The exact syntax of a database connection URL is specified by the Hive documentation. For example, the server principal is often included "
                    + "as a connection parameter when connecting to a secure Hive server.")
            .defaultValue(null)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .required(true)
            .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
            .build();

    public static final PropertyDescriptor HIVE_CONFIGURATION_RESOURCES = new PropertyDescriptor.Builder()
            .name("hive-config-resources")
            .displayName("Hive Configuration Resources")
            .description("A file or comma separated list of files which contains the Hive configuration (hive-site.xml, e.g.). Without this, Hadoop "
                    + "will search the classpath for a 'hive-site.xml' file or will revert to a default configuration. Note that to enable authentication "
                    + "with Kerberos e.g., the appropriate properties must be set in the configuration files. Please see the Hive documentation for more details.")
            .required(false)
            .identifiesExternalResource(ResourceCardinality.MULTIPLE, ResourceType.FILE)
            .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
            .build();

    public static final PropertyDescriptor DB_USER = new PropertyDescriptor.Builder()
            .name("hive-db-user")
            .displayName("Database User")
            .description("Database user name")
            .defaultValue(null)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
            .build();

    public static final PropertyDescriptor DB_PASSWORD = new PropertyDescriptor.Builder()
            .name("hive-db-password")
            .displayName("Password")
            .description("The password for the database user")
            .defaultValue(null)
            .required(false)
            .sensitive(true)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
            .build();

    public static final PropertyDescriptor MAX_WAIT_TIME = new PropertyDescriptor.Builder()
            .name("hive-max-wait-time")
            .displayName("Max Wait Time")
            .description("The maximum amount of time that the pool will wait (when there are no available connections) "
                    + " for a connection to be returned before failing, or -1 to wait indefinitely. ")
            .defaultValue("500 millis")
            .required(true)
            .addValidator(StandardValidators.TIME_PERIOD_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
            .build();

    public static final PropertyDescriptor MAX_TOTAL_CONNECTIONS = new PropertyDescriptor.Builder()
            .name("hive-max-total-connections")
            .displayName("Max Total Connections")
            .description("The maximum number of active connections that can be allocated from this pool at the same time, "
                    + "or negative for no limit.")
            .defaultValue("8")
            .required(true)
            .addValidator(StandardValidators.INTEGER_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
            .build();

    public static final PropertyDescriptor MAX_CONN_LIFETIME = new PropertyDescriptor.Builder()
            .displayName("Max Connection Lifetime")
            .name("hive-max-conn-lifetime")
            .description("The maximum lifetime in milliseconds of a connection. After this time is exceeded the " +
                    "connection pool will invalidate the connection. A value of zero or -1 " +
                    "means the connection has an infinite lifetime.")
            .defaultValue(DEFAULT_MAX_CONN_LIFETIME)
            .required(true)
            .addValidator(DBCPValidator.CUSTOM_TIME_PERIOD_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
            .build();

    public static final PropertyDescriptor VALIDATION_QUERY = new PropertyDescriptor.Builder()
            .name("Validation-query")
            .displayName("Validation query")
            .description("Validation query used to validate connections before returning them. "
                    + "When a borrowed connection is invalid, it gets dropped and a new valid connection will be returned. "
                    + "NOTE: Using validation may have a performance penalty.")
            .required(false)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
            .build();

    static final PropertyDescriptor KERBEROS_CREDENTIALS_SERVICE = new PropertyDescriptor.Builder()
        .name("kerberos-credentials-service")
        .displayName("Kerberos Credentials Service")
        .description("Specifies the Kerberos Credentials Controller Service that should be used for authenticating with Kerberos")
        .identifiesControllerService(KerberosCredentialsService.class)
        .required(false)
        .build();

    static final PropertyDescriptor KERBEROS_PRINCIPAL = new PropertyDescriptor.Builder()
            .name("kerberos-principal")
            .displayName("Kerberos Principal")
            .description("The principal to use when specifying the principal and password directly in the processor for authenticating via Kerberos.")
            .required(false)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .addValidator(StandardValidators.createAttributeExpressionLanguageValidator(AttributeExpression.ResultType.STRING))
            .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
            .build();

    static final PropertyDescriptor KERBEROS_PASSWORD = new PropertyDescriptor.Builder()
            .name("kerberos-password")
            .displayName("Kerberos Password")
            .description("The password to use when specifying the principal and password directly in the processor for authenticating via Kerberos.")
            .required(false)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .sensitive(true)
            .build();


    private List<PropertyDescriptor> properties;

    private String connectionUrl = "unknown";

    // Holder of cached Configuration information so validation does not reload the same config over and over
    private final AtomicReference<ValidationResources> validationResourceHolder = new AtomicReference<>();

    private volatile BasicDataSource dataSource;

    private volatile HiveConfigurator hiveConfigurator = new HiveConfigurator();
    private volatile UserGroupInformation ugi;
    private final AtomicReference<KerberosUser> kerberosUserReference = new AtomicReference<>();

    @Override
    protected void init(final ControllerServiceInitializationContext context) {
        List<PropertyDescriptor> props = new ArrayList<>();
        props.add(DATABASE_URL);
        props.add(HIVE_CONFIGURATION_RESOURCES);
        props.add(DB_USER);
        props.add(DB_PASSWORD);
        props.add(MAX_WAIT_TIME);
        props.add(MAX_TOTAL_CONNECTIONS);
        props.add(MAX_CONN_LIFETIME);
        props.add(VALIDATION_QUERY);
        props.add(KERBEROS_CREDENTIALS_SERVICE);
        props.add(KERBEROS_PRINCIPAL);
        props.add(KERBEROS_PASSWORD);

        properties = props;
    }

    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return properties;
    }

    @Override
    protected Collection<ValidationResult> customValidate(ValidationContext validationContext) {
        boolean confFileProvided = validationContext.getProperty(HIVE_CONFIGURATION_RESOURCES).isSet();

        final List<ValidationResult> problems = new ArrayList<>();

        if (confFileProvided) {
            final KerberosCredentialsService credentialsService = validationContext.getProperty(KERBEROS_CREDENTIALS_SERVICE).asControllerService(KerberosCredentialsService.class);
            final String explicitPrincipal = validationContext.getProperty(KERBEROS_PRINCIPAL).evaluateAttributeExpressions().getValue();
            final String explicitPassword = validationContext.getProperty(KERBEROS_PASSWORD).getValue();

            final String resolvedPrincipal;
            final String resolvedKeytab;
            if (credentialsService != null) {
                resolvedPrincipal = credentialsService.getPrincipal();
                resolvedKeytab = credentialsService.getKeytab();
            } else {
                resolvedPrincipal = explicitPrincipal;
                resolvedKeytab = null;
            }

            final String configFiles = validationContext.getProperty(HIVE_CONFIGURATION_RESOURCES).evaluateAttributeExpressions().getValue();
            problems.addAll(hiveConfigurator.validate(configFiles, resolvedPrincipal, resolvedKeytab, explicitPassword, validationResourceHolder, getLogger()));

            if (credentialsService != null && (explicitPrincipal != null || explicitPassword != null)) {
                problems.add(new ValidationResult.Builder()
                        .subject(KERBEROS_CREDENTIALS_SERVICE.getDisplayName())
                        .valid(false)
                        .explanation("kerberos principal/password and kerberos credential service cannot be configured at the same time")
                        .build());
            }
        }

        return problems;
    }

    /**
     * Configures connection pool by creating an instance of the
     * {@link BasicDataSource} based on configuration provided with
     * {@link ConfigurationContext}.
     * <p>
     * This operation makes no guarantees that the actual connection could be
     * made since the underlying system may still go off-line during normal
     * operation of the connection pool.
     * <p/>
     * As of Apache NiFi 1.5.0, due to changes made to
     * {@link SecurityUtil#loginKerberos(Configuration, String, String)}, which is used by this class invoking
     * {@link HiveConfigurator#authenticate(Configuration, String, String)}
     * to authenticate a principal with Kerberos, Hive controller services no longer use a separate thread to
     * relogin, and instead call {@link UserGroupInformation#checkTGTAndReloginFromKeytab()} from
     * {@link Hive_1_1ConnectionPool#getConnection()}.  The relogin request is performed in a synchronized block to prevent
     * threads from requesting concurrent relogins.  For more information, please read the documentation for
     * {@link SecurityUtil#loginKerberos(Configuration, String, String)}.
     * <p/>
     * In previous versions of NiFi, a {@link org.apache.nifi.hadoop.KerberosTicketRenewer} was started by
     * {@link HiveConfigurator#authenticate(Configuration, String, String, long)} when the Hive
     * controller service was enabled.  The use of a separate thread to explicitly relogin could cause race conditions
     * with the implicit relogin attempts made by hadoop/Hive code on a thread that references the same
     * {@link UserGroupInformation} instance.  One of these threads could leave the
     * {@link javax.security.auth.Subject} in {@link UserGroupInformation} to be cleared or in an unexpected state
     * while the other thread is attempting to use the {@link javax.security.auth.Subject}, resulting in failed
     * authentication attempts that would leave the Hive controller service in an unrecoverable state.
     *
     * @see SecurityUtil#loginKerberos(Configuration, String, String)
     * @see HiveConfigurator#authenticate(Configuration, String, String)
     * @see HiveConfigurator#authenticate(Configuration, String, String, long)
     * @param context the configuration context
     * @throws InitializationException if unable to create a database connection
     */
    @OnEnabled
    public void onConfigured(final ConfigurationContext context) throws InitializationException {

        ComponentLog log = getLogger();

        final String configFiles = context.getProperty(HIVE_CONFIGURATION_RESOURCES).evaluateAttributeExpressions().getValue();
        final Configuration hiveConfig = hiveConfigurator.getConfigurationFromFiles(configFiles);
        final String validationQuery = context.getProperty(VALIDATION_QUERY).evaluateAttributeExpressions().getValue();

        // add any dynamic properties to the Hive configuration
        for (final Map.Entry<PropertyDescriptor, String> entry : context.getProperties().entrySet()) {
            final PropertyDescriptor descriptor = entry.getKey();
            if (descriptor.isDynamic()) {
                hiveConfig.set(descriptor.getName(), context.getProperty(descriptor).evaluateAttributeExpressions().getValue());
            }
        }

        final String drv = HiveDriver.class.getName();
        if (SecurityUtil.isSecurityEnabled(hiveConfig)) {
            final String explicitPrincipal = context.getProperty(KERBEROS_PRINCIPAL).evaluateAttributeExpressions().getValue();
            final String explicitPassword = context.getProperty(KERBEROS_PASSWORD).getValue();
            final KerberosCredentialsService credentialsService = context.getProperty(KERBEROS_CREDENTIALS_SERVICE).asControllerService(KerberosCredentialsService.class);

            final String resolvedPrincipal;
            final String resolvedKeytab;
            if (credentialsService != null) {
                resolvedPrincipal = credentialsService.getPrincipal();
                resolvedKeytab = credentialsService.getKeytab();
            } else {
                resolvedPrincipal = explicitPrincipal;
                resolvedKeytab = null;
            }

            if (resolvedKeytab != null) {
                kerberosUserReference.set(new KerberosKeytabUser(resolvedPrincipal, resolvedKeytab));
                log.info("Hive Security Enabled, logging in as principal {} with keytab {}", resolvedPrincipal, resolvedKeytab);
            } else if (explicitPassword != null) {
                kerberosUserReference.set(new KerberosPasswordUser(resolvedPrincipal, explicitPassword));
                log.info("Hive Security Enabled, logging in as principal {} with password", resolvedPrincipal);
            } else {
                throw new InitializationException("Unable to authenticate with Kerberos, no keytab or password was provided");
            }

            try {
                ugi = hiveConfigurator.authenticate(hiveConfig, kerberosUserReference.get());
            } catch (AuthenticationFailedException ae) {
                log.error(ae.getMessage(), ae);
                throw new InitializationException(ae);
            }

            getLogger().info("Successfully logged in as principal " + resolvedPrincipal);
        }

        final String user = context.getProperty(DB_USER).evaluateAttributeExpressions().getValue();
        final String passw = context.getProperty(DB_PASSWORD).evaluateAttributeExpressions().getValue();
        final Long maxWaitMillis = context.getProperty(MAX_WAIT_TIME).evaluateAttributeExpressions().asTimePeriod(TimeUnit.MILLISECONDS);
        final Integer maxTotal = context.getProperty(MAX_TOTAL_CONNECTIONS).evaluateAttributeExpressions().asInteger();
        final long maxConnectionLifetimeMillis = extractMillisWithInfinite(context.getProperty(MAX_CONN_LIFETIME).evaluateAttributeExpressions());

        dataSource = new BasicDataSource();
        dataSource.setDriverClassName(drv);

        connectionUrl = context.getProperty(DATABASE_URL).evaluateAttributeExpressions().getValue();

        dataSource.setMaxWaitMillis(maxWaitMillis);
        dataSource.setMaxTotal(maxTotal);
        dataSource.setMaxConnLifetimeMillis(maxConnectionLifetimeMillis);

        if (validationQuery != null && !validationQuery.isEmpty()) {
            dataSource.setValidationQuery(validationQuery);
            dataSource.setTestOnBorrow(true);
        }

        dataSource.setUrl(connectionUrl);
        dataSource.setUsername(user);
        dataSource.setPassword(passw);
    }

    /**
     * Shutdown pool, close all open connections.
     */
    @OnDisabled
    public void shutdown() {
        try {
            if(dataSource != null) {
                dataSource.close();
            }
        } catch (final SQLException e) {
            throw new ProcessException(e);
        }
    }

    @Override
    public Connection getConnection() throws ProcessException {
        try {
            if (ugi != null) {
                /*
                 * Explicitly check the TGT and relogin if necessary with the KerberosUser instance.  No synchronization
                 * is necessary in the client code, since AbstractKerberosUser's checkTGTAndRelogin method is synchronized.
                 */
                getLogger().trace("getting UGI instance");
                if (kerberosUserReference.get() != null) {
                    // if there's a KerberosUser associated with this UGI, check the TGT and relogin if it is close to expiring
                    KerberosUser kerberosUser = kerberosUserReference.get();
                    getLogger().debug("kerberosUser is " + kerberosUser);
                    try {
                        getLogger().debug("checking TGT on kerberosUser " + kerberosUser);
                        kerberosUser.checkTGTAndRelogin();
                    } catch (final KerberosLoginException e) {
                        throw new ProcessException("Unable to relogin with kerberos credentials for " + kerberosUser.getPrincipal(), e);
                    }
                } else {
                    getLogger().debug("kerberosUser was null, will not refresh TGT with KerberosUser");
                    // no synchronization is needed for UserGroupInformation.checkTGTAndReloginFromKeytab; UGI handles the synchronization internally
                    ugi.checkTGTAndReloginFromKeytab();
                }
                try {
                    return ugi.doAs((PrivilegedExceptionAction<Connection>) () -> dataSource.getConnection());
                } catch (UndeclaredThrowableException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof SQLException) {
                        throw (SQLException) cause;
                    } else {
                        throw e;
                    }
                }
            } else {
                getLogger().info("Simple Authentication");
                return dataSource.getConnection();
            }
        } catch (SQLException | IOException | InterruptedException e) {
            getLogger().error("Error getting Hive connection", e);
            throw new ProcessException(e);
        }
    }

    @Override
    public String toString() {
        return "HiveConnectionPool[id=" + getIdentifier() + "]";
    }

    @Override
    public String getConnectionURL() {
        return connectionUrl;
    }

    private long extractMillisWithInfinite(PropertyValue prop) {
        if (prop.getValue() == null || DEFAULT_MAX_CONN_LIFETIME.equals(prop.getValue())) {
            return -1;
        } else {
            return prop.asTimePeriod(TimeUnit.MILLISECONDS);
        }
    }
}
