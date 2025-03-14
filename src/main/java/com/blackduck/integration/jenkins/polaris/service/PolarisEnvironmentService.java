/*
 * blackduck-coverity-on-polaris
 *
 * Copyright ©2024 Black Duck Software, Inc. All rights reserved.
 * Black Duck® is a trademark of Black Duck Software, Inc. in the United States and other countries.
 */
package com.blackduck.integration.jenkins.polaris.service;

import com.blackduck.integration.jenkins.polaris.PolarisJenkinsEnvironmentVariable;
import com.blackduck.integration.polaris.common.configuration.PolarisServerConfigBuilder;
import com.blackduck.integration.polaris.common.exception.PolarisIntegrationException;
import com.blackduck.integration.util.IntEnvironmentVariables;
import java.util.Map;
import java.util.function.BiConsumer;
import org.apache.commons.lang3.StringUtils;

public class PolarisEnvironmentService {
    private final Map<String, String> environmentVariables;

    public PolarisEnvironmentService(Map<String, String> environmentVariables) {
        this.environmentVariables = environmentVariables;
    }

    public IntEnvironmentVariables createPolarisEnvironment(
            String changeSetFileRemotePath, PolarisServerConfigBuilder polarisServerConfigBuilder)
            throws PolarisIntegrationException {
        IntEnvironmentVariables intEnvironmentVariables = IntEnvironmentVariables.empty();
        intEnvironmentVariables.putAll(environmentVariables);

        if (StringUtils.isNotBlank(changeSetFileRemotePath)) {
            intEnvironmentVariables.put(
                    PolarisJenkinsEnvironmentVariable.CHANGE_SET_FILE_PATH.stringValue(), changeSetFileRemotePath);
        }

        polarisServerConfigBuilder
                .getProperties()
                .forEach((builderPropertyKey, propertyValue) ->
                        acceptIfNotNull(intEnvironmentVariables::put, builderPropertyKey.getKey(), propertyValue));

        try {
            polarisServerConfigBuilder.build().populateEnvironmentVariables(intEnvironmentVariables::put);
        } catch (IllegalArgumentException ex) {
            throw new PolarisIntegrationException(
                    "There is a problem with your Coverity on Polaris system configuration", ex);
        }

        return intEnvironmentVariables;
    }

    public IntEnvironmentVariables getInitialEnvironment() {
        IntEnvironmentVariables intEnvironmentVariables = IntEnvironmentVariables.empty();

        intEnvironmentVariables.putAll(environmentVariables);

        return intEnvironmentVariables;
    }

    private void acceptIfNotNull(BiConsumer<String, String> environmentPutter, String key, String value) {
        if (StringUtils.isNoneBlank(key, value)) {
            environmentPutter.accept(key, value);
        }
    }
}
