package org.apache.nifi.registry.security.ldap.tenants.ad;

import com.google.common.collect.Sets;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.apache.nifi.registry.security.authorization.AuthorizerConfigurationContext;
import org.apache.nifi.registry.security.authorization.Group;
import org.apache.nifi.registry.security.authorization.User;
import org.apache.nifi.registry.security.authorization.UserAndGroups;
import org.apache.nifi.registry.security.authorization.UserGroupProviderInitializationContext;
import org.apache.nifi.registry.security.authorization.exception.AuthorizationAccessException;

public class UserGroups {
  private final Set<Group> groups;
  private final Map<String, Group> idToGroup;
  private final Set<User> users;
  private final Map<String, User> idToUser;
  private final Map<User, Set<Group>> userToGroups;

  public UserGroups(String groupName, Set<String> nodes) {
    final Group group = (new Group.Builder()).name(groupName).identifier(groupName).addUsers(nodes).build();
    this.groups = Collections.unmodifiableSet(Sets.newHashSet(new Group[]{group}));
    this.idToGroup = Collections.unmodifiableMap(new HashMap<String, Group>() {
      {
        this.put(group.getIdentifier(), group);
      }
    });
    Set<User> users = new HashSet();
    Map<String, User> idToUser = new HashMap();
    Map<User, Set<Group>> userToGroups = new HashMap();
    nodes.stream().map((node) -> this.toUser(node)).forEach((user) -> {
      idToUser.put(user.getIdentifier(), user);
      users.add(user);
      userToGroups.computeIfAbsent(user, (__) -> Collections.unmodifiableSet(Sets.newHashSet(new Group[]{group})));
    });
    this.users = Collections.unmodifiableSet(users);
    this.idToUser = Collections.unmodifiableMap(idToUser);
    this.userToGroups = Collections.unmodifiableMap(userToGroups);
  }

  private User toUser(String node) {
    User user = (new User.Builder()).identity(node).identifier(node).build();
    return user;
  }

  public Set<User> getUsers() throws AuthorizationAccessException {
    return this.users;
  }

  public User getUser(String identifier) throws AuthorizationAccessException {
    return (User)this.idToUser.get(identifier);
  }

  public User getUserByIdentity(String identity) throws AuthorizationAccessException {
    return (User)this.idToUser.get(identity);
  }

  public Set<Group> getGroups() throws AuthorizationAccessException {
    return this.groups;
  }

  public Group getGroup(String identifier) throws AuthorizationAccessException {
    return (Group)this.idToGroup.get(identifier);
  }

  public UserAndGroups getUserAndGroups(String identity) throws AuthorizationAccessException {
    final User user = (User)this.idToUser.get(identity);
    final Set<Group> groups = user == null ? null : (Set)this.userToGroups.get(user);
    return new UserAndGroups() {
      public User getUser() {
        return user;
      }

      public Set<Group> getGroups() {
        return groups;
      }
    };
  }

  public void initialize(UserGroupProviderInitializationContext initializationContext) throws RuntimeException {
  }

  public void onConfigured(AuthorizerConfigurationContext configurationContext) throws RuntimeException {
  }

  public void preDestruction() throws RuntimeException {
  }
}
