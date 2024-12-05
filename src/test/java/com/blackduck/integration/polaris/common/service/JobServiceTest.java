package com.blackduck.integration.polaris.common.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import com.blackduck.integration.exception.IntegrationException;
import com.blackduck.integration.log.LogLevel;
import com.blackduck.integration.log.PrintStreamIntLogger;
import com.blackduck.integration.log.SilentIntLogger;
import com.blackduck.integration.polaris.common.api.PolarisResource;
import com.blackduck.integration.polaris.common.api.model.JobAttributes;
import com.blackduck.integration.polaris.common.api.model.JobStatus;
import com.blackduck.integration.polaris.common.request.PolarisRequestFactory;
import com.blackduck.integration.polaris.common.rest.AccessTokenPolarisHttpClient;
import com.blackduck.integration.rest.HttpUrl;
import com.blackduck.integration.rest.request.Request;
import com.blackduck.integration.rest.response.Response;
import com.google.gson.Gson;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatcher;
import org.mockito.Mockito;

public class JobServiceTest {
    @Test
    public void testGetJobByUrl() throws IntegrationException {
        AccessTokenPolarisHttpClient polarisHttpClient = Mockito.mock(AccessTokenPolarisHttpClient.class);
        HttpUrl jobsApi = new HttpUrl("https://polaris.blackduck.example.com/api/jobs/jobs/p10t3j6grt67pabjgp89djvln4");
        mockClientBehavior(polarisHttpClient, jobsApi, "jobservice_status.json");

        PolarisJsonTransformer polarisJsonTransformer =
                new PolarisJsonTransformer(new Gson(), new PrintStreamIntLogger(System.out, LogLevel.INFO));
        PolarisService polarisService =
                new PolarisService(polarisHttpClient, polarisJsonTransformer, PolarisRequestFactory.DEFAULT_LIMIT);

        JobService jobService = new JobService(new SilentIntLogger(), polarisService);
        PolarisResource<JobAttributes> jobResource = jobService.getJobByUrl(jobsApi);
        JobStatus jobStatus = jobResource.getAttributes().getStatus();

        assertEquals(Integer.valueOf(100), jobStatus.getProgress());
        assertEquals(JobStatus.StateEnum.COMPLETED, jobStatus.getState());
    }

    @Test
    public void testGetJobByUrlOsra() throws IntegrationException {
        AccessTokenPolarisHttpClient polarisHttpClient = Mockito.mock(AccessTokenPolarisHttpClient.class);
        HttpUrl opsraApi = new HttpUrl(
                "https://polaris.blackduck.example.com/api/tds-sca/v0/bdio/status?scan-id=5ed9ed6e-f9b7-4ea8-8255-ec6104f72437");
        mockClientBehavior(polarisHttpClient, opsraApi, "osra_status.json");

        PolarisJsonTransformer polarisJsonTransformer =
                new PolarisJsonTransformer(new Gson(), new PrintStreamIntLogger(System.out, LogLevel.INFO));
        PolarisService polarisService =
                new PolarisService(polarisHttpClient, polarisJsonTransformer, PolarisRequestFactory.DEFAULT_LIMIT);

        JobService jobService = new JobService(new SilentIntLogger(), polarisService);
        PolarisResource<JobAttributes> jobResource = jobService.getJobByUrl(opsraApi);
        JobStatus jobStatus = jobResource.getAttributes().getStatus();

        assertEquals(Integer.valueOf(97), jobStatus.getProgress());
        assertEquals(JobStatus.StateEnum.RUNNING, jobStatus.getState());
    }

    private void mockClientBehavior(AccessTokenPolarisHttpClient polarisHttpClient, HttpUrl uri, String results) {
        try {
            Response response = Mockito.mock(Response.class);
            Mockito.when(response.getContentString()).thenReturn(getPreparedContentStringFrom(results));

            ArgumentMatcher<Request> isMockedRequest =
                    request -> null != request && request.getUrl().equals(uri);
            Mockito.when(polarisHttpClient.execute(Mockito.argThat(isMockedRequest)))
                    .thenReturn(response);
        } catch (IOException | IntegrationException e) {
            fail(
                    "Unexpected " + e.getClass()
                            + " was thrown while mocking client behavior. Please check the test for errors.",
                    e);
        }
    }

    private String getPreparedContentStringFrom(String resourceName) throws IOException {
        try (InputStream inputStream = getClass().getResourceAsStream("/JobService/" + resourceName)) {
            if (inputStream == null) {
                throw new IOException("Resource not found: " + resourceName);
            }
            return IOUtils.toString(inputStream, StandardCharsets.UTF_8);
        }
    }
}
