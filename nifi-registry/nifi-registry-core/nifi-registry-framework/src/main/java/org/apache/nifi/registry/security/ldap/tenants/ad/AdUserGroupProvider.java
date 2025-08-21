package org.apache.nifi.registry.security.ldap.tenants.ad;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.nifi.registry.security.authorization.AuthorizerConfigurationContext;
import org.apache.nifi.registry.security.authorization.Group;
import org.apache.nifi.registry.security.authorization.User;
import org.apache.nifi.registry.security.authorization.UserAndGroups;
import org.apache.nifi.registry.security.authorization.UserGroupProviderInitializationContext;
import org.apache.nifi.registry.security.authorization.exception.AuthorizationAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AdUserGroupProvider {

  public static final String NIFI_GROUP = "NiFi Group";
  public static final String NIFI_NODES_PROPERTIES_LOCATION = "NiFi Nodes Properties Location";
  public static final String NIFI_REGISTRY_NODES_PROPERTIES_LOCATION = "NiFi Registry Nodes Properties Location";
  public static final String KNOX_NODES_PROPERTIES_LOCATION = "Knox Nodes Properties Location";
  public static final String INFER_UNQUALIFIED_HOSTNAMES = "Infer Unqualified Hostnames";
  public static final String HOSTNAME_IDENTITY_TRANSFORM = "Hostname Identity Transform";
  private final Logger logger = LoggerFactory.getLogger(this.getClass());
  private String group;
  private Path nifiNodesPropertiesPath;
  private Path knoxNodesPropertiesPath;
  private Path nifiRegistryNodesPropertiesPath;
  private boolean inferUnqualifiedHostnames;
  private Transform transform;
  private final ConfigReader nodeReader = new ConfigReader();
  private final AtomicReference<UserGroups> userGroupHolderWrapper = new AtomicReference();
  private int pollConfigPeriodSeconds = 30;
  private final ScheduledExecutorService refreshExecutor = Executors.newSingleThreadScheduledExecutor((new ThreadFactoryBuilder()).setNameFormat(this.getClass().getSimpleName() + "-refresher-%d").build());

  public void initialize(UserGroupProviderInitializationContext initializationContext) throws RuntimeException {
  }

  public void onConfigured(AuthorizerConfigurationContext configurationContext) throws RuntimeException {
    Map<String, String> properties = configurationContext.getProperties();
    this.group = (String)properties.get("NiFi Group");
    this.nifiNodesPropertiesPath = this.getNodeConfigPath("NiFi Nodes Properties Location", properties);
    this.nifiRegistryNodesPropertiesPath = this.getNodeConfigPath("NiFi Registry Nodes Properties Location", properties);
    this.knoxNodesPropertiesPath = this.getNodeConfigPath("Knox Nodes Properties Location", properties);
    this.transform = this.getTransform(properties);
    String inferUnqualifiedHostnamesValue = (String)properties.get("Infer Unqualified Hostnames");
    if (inferUnqualifiedHostnamesValue != null && "true".equals(inferUnqualifiedHostnamesValue)) {
      this.inferUnqualifiedHostnames = true;
    }

    UserGroups newUserGroups = new UserGroups(this.group, Collections.emptySet());
    this.userGroupHolderWrapper.set(newUserGroups);
    this.refreshExecutor.scheduleAtFixedRate(() -> this.refresh(), 0L, (long)this.pollConfigPeriodSeconds, TimeUnit.SECONDS);
  }

  private Transform getTransform(Map<String, String> properties) {
    try {
      return properties.get("Hostname Identity Transform") == null ? AdUserGroupProvider.Transform.NONE : AdUserGroupProvider.Transform.valueOf(((String)properties.get("Hostname Identity Transform")).trim().toUpperCase());
    } catch (IllegalArgumentException var3) {
      this.logger.warn("Invalid {} parameter value '{}', no transform will be applied.", "Hostname Identity Transform", properties.get("Hostname Identity Transform"));
      return AdUserGroupProvider.Transform.NONE;
    }
  }

  private Path getNodeConfigPath(String propertyName, Map<String, String> properties) {
    String nodeConfigLocation = (String)properties.get(propertyName);
    if (nodeConfigLocation != null && !nodeConfigLocation.trim().isEmpty()) {
      try {
        Path nodeConfigPath = Paths.get(nodeConfigLocation);
        File nodeConfigFile = nodeConfigPath.toFile();
        if (!nodeConfigFile.exists()) {
          this.logger.warn("'{}' does not exist at specified location '{}'", new Object[]{propertyName, nodeConfigFile.getAbsolutePath()});
          return null;
        } else {
          return nodeConfigPath;
        }
      } catch (Exception e) {
        this.logger.error("Unable to create path for '{}' at '{}'", new Object[]{propertyName, nodeConfigLocation}, e);
        return null;
      }
    } else {
      this.logger.warn("'{}' is not specified, node config will not be loaded", new Object[]{nodeConfigLocation});
      return null;
    }
  }

  public void preDestruction() throws RuntimeException {
    this.getUserGroupHolder().preDestruction();

    try {
      this.refreshExecutor.shutdownNow();
    } catch (Exception var2) {
      this.logger.error("Error while shutting down refreshExecutor.");
    }

  }

  protected void refresh() {
    this.logger.debug("Beginning refresh...");

    try {
      Set<String> nifiNodes = this.nodeReader.getNodes(this.nifiNodesPropertiesPath);
      Set<String> knoxNodes = this.nodeReader.getNodes(this.knoxNodesPropertiesPath);
      Set<String> nifiRegistryNodes = this.nodeReader.getNodes(this.nifiRegistryNodesPropertiesPath);
      Set<String> nodes = new HashSet();
      nodes.addAll(nifiNodes);
      nodes.addAll(knoxNodes);
      nodes.addAll(nifiRegistryNodes);
      nodes = (Set)nodes.stream().map(this.transform.getFunction()).collect(Collectors.toSet());
      if (this.inferUnqualifiedHostnames) {
        Set<String> inferredNodes = this.getInferredNodes(nodes);
        nodes.addAll(inferredNodes);
      }

      if (this.logger.isDebugEnabled()) {
        nodes.forEach((n) -> this.logger.debug("Found node [{}]", new Object[]{n}));
      }

      UserGroups newUserGroups = new UserGroups(this.group, nodes);
      UserGroups oldUserGroups = (UserGroups)this.userGroupHolderWrapper.get();
      this.userGroupHolderWrapper.set(newUserGroups);
      Optional.ofNullable(oldUserGroups).ifPresent(UserGroups::preDestruction);
      this.logger.debug("Finished refresh");
    } catch (Throwable e) {
      this.logger.error("Error while refreshing user/group settings.", e);
    }

  }

  private Set<String> getInferredNodes(Set<String> nodes) {
    Set<String> inferredNodes = new HashSet();

    for(String node : nodes) {
      int firstSeparator = node.indexOf(".");
      if (firstSeparator > 0) {
        String inferredNode = node.substring(0, firstSeparator);
        inferredNodes.add(inferredNode);
      }
    }

    return inferredNodes;
  }

  public Set<User> getUsers() throws AuthorizationAccessException {
    return this.getUserGroupHolder().getUsers();
  }

  public User getUser(String identifier) throws AuthorizationAccessException {
    return this.getUserGroupHolder().getUser(identifier);
  }

  public User getUserByIdentity(String identity) throws AuthorizationAccessException {
    return this.getUserGroupHolder().getUserByIdentity(identity);
  }

  public Set<Group> getGroups() throws AuthorizationAccessException {
    return this.getUserGroupHolder().getGroups();
  }

  public Group getGroup(String identifier) throws AuthorizationAccessException {
    return this.getUserGroupHolder().getGroup(identifier);
  }

  public UserAndGroups getUserAndGroups(String identity) throws AuthorizationAccessException {
    return this.getUserGroupHolder().getUserAndGroups(identity);
  }

  private UserGroups getUserGroupHolder() {
    return (UserGroups)this.userGroupHolderWrapper.get();
  }

  void setPollConfigPeriodSeconds(int pollConfigPeriodSeconds) {
    this.pollConfigPeriodSeconds = pollConfigPeriodSeconds;
  }

  private static enum Transform {
    UPPER(String::toUpperCase),
    LOWER(String::toLowerCase),
    NONE((s) -> s);

    private final Function<String, String> function;

    private Transform(Function<String, String> transformFunction) {
      this.function = transformFunction;
    }

    public Function<String, String> getFunction() {
      return this.function;
    }
  }

}
