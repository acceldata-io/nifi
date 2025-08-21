package org.apache.nifi.registry.security.ldap.tenants.ad;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ConfigReader {
  private static final Logger LOGGER = LoggerFactory.getLogger(ConfigReader.class);

  public Set<String> getNodes(Path cmConfigFilePath) {
    if (cmConfigFilePath == null) {
      LOGGER.debug("Config path was null, no nodes to read");
      return Collections.emptySet();
    } else {
      try {
        List<String> properties = Files.readAllLines(cmConfigFilePath);
        Set<String> nodes = (Set)properties.stream().map((property) -> property.split("[:=]")).filter((propertyKeyValue) -> propertyKeyValue.length > 1).map((propertyKeyValue) -> propertyKeyValue[0]).collect(Collectors.toSet());
        return nodes;
      } catch (Exception e) {
        LOGGER.error(e.getMessage(), e);
        return Collections.emptySet();
      }
    }
  }
}
