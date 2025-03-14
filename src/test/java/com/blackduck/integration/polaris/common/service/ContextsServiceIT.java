package com.blackduck.integration.polaris.common.service;

import static org.junit.Assert.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.blackduck.integration.exception.IntegrationException;
import com.blackduck.integration.log.IntLogger;
import com.blackduck.integration.log.LogLevel;
import com.blackduck.integration.log.PrintStreamIntLogger;
import com.blackduck.integration.polaris.common.api.PolarisResource;
import com.blackduck.integration.polaris.common.api.model.ContextAttributes;
import com.blackduck.integration.polaris.common.configuration.PolarisServerConfig;
import com.blackduck.integration.polaris.common.configuration.PolarisServerConfigBuilder;
import com.google.gson.Gson;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.platform.commons.util.StringUtils;

public class ContextsServiceIT {
    private ContextsService contextsService;

    @BeforeEach
    public void createContextsService() {
        PolarisServerConfigBuilder polarisServerConfigBuilder = PolarisServerConfig.newBuilder();
        polarisServerConfigBuilder.setUrl(System.getenv("TEST_POLARIS_URL"));
        polarisServerConfigBuilder.setAccessToken(System.getenv("POLARIS_ACCESS_TOKEN"));
        polarisServerConfigBuilder.setGson(new Gson());

        assumeTrue(StringUtils.isNotBlank(polarisServerConfigBuilder.getUrl()));
        assumeTrue(StringUtils.isNotBlank(polarisServerConfigBuilder.getAccessToken()));

        PolarisServerConfig polarisServerConfig = polarisServerConfigBuilder.build();
        IntLogger logger = new PrintStreamIntLogger(System.out, LogLevel.INFO);
        PolarisServicesFactory polarisServicesFactory = polarisServerConfig.createPolarisServicesFactory(logger);

        contextsService = polarisServicesFactory.createContextsService();
    }

    @Test
    public void testGetCurrentContext() {
        try {
            Optional<PolarisResource<ContextAttributes>> currentContext = contextsService.getCurrentContext();
            if (currentContext.isPresent()) {
                assertTrue(currentContext
                        .map(PolarisResource::getAttributes)
                        .map(ContextAttributes::getCurrent)
                        .orElse(Boolean.FALSE));
            } else {
                assertTrue(contextsService.getAllContexts().stream()
                        .map(PolarisResource::getAttributes)
                        .map(ContextAttributes::getCurrent)
                        .noneMatch(Boolean.TRUE::equals));
            }
        } catch (IntegrationException e) {
            fail("ContextsService encountered an unexpected exception when retrieving all contexts:", e);
        }
    }
}
