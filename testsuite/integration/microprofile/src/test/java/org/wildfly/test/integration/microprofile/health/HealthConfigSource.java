package org.wildfly.test.integration.microprofile.health;

import org.eclipse.microprofile.config.spi.ConfigSource;

import java.util.HashMap;
import java.util.Map;

public class HealthConfigSource implements ConfigSource {

    private final HashMap<String, String> props;

    public HealthConfigSource() {
        props = new HashMap<>();
        props.put("org.wildfly.test.integration.microprofile.health.MyProbe.propertyConfiguredByTheDeployment", Boolean.TRUE.toString());
    }

    @Override
    public Map<String, String> getProperties() {
        return props;
    }

    @Override
    public String getValue(String propertyName) {
        return props.get(propertyName);
    }

    @Override
    public String getName() {
        return "ConfigSource local to the deployment";
    }
}
