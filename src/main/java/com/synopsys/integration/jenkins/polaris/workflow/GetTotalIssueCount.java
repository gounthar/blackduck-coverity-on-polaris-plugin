/**
 * synopsys-polaris
 *
 * Copyright (c) 2020 Synopsys, Inc.
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.synopsys.integration.jenkins.polaris.workflow;

import java.util.Objects;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;

import com.synopsys.integration.exception.IntegrationException;
import com.synopsys.integration.jenkins.extensions.JenkinsIntLogger;
import com.synopsys.integration.polaris.common.api.query.model.CountV0;
import com.synopsys.integration.polaris.common.api.query.model.CountV0Attributes;
import com.synopsys.integration.polaris.common.api.query.model.CountV0Resources;
import com.synopsys.integration.polaris.common.cli.PolarisCliResponseUtility;
import com.synopsys.integration.polaris.common.cli.model.CliCommonResponseModel;
import com.synopsys.integration.polaris.common.cli.model.CommonIssueSummary;
import com.synopsys.integration.polaris.common.cli.model.CommonScanInfo;
import com.synopsys.integration.polaris.common.cli.model.CommonToolInfo;
import com.synopsys.integration.polaris.common.exception.PolarisIntegrationException;
import com.synopsys.integration.polaris.common.request.PolarisRequestFactory;
import com.synopsys.integration.polaris.common.service.JobService;
import com.synopsys.integration.polaris.common.service.PolarisService;
import com.synopsys.integration.stepworkflow.SubStep;
import com.synopsys.integration.stepworkflow.SubStepResponse;

public class GetTotalIssueCount implements SubStep<String, Integer> {
    public static final String STEP_EXCEPTION_PREFIX = "Issue count for most recent Polaris Analysis could not be determined: ";
    private final JenkinsIntLogger logger;
    private final PolarisService polarisService;
    private final JobService jobService;
    private final Integer jobTimeoutInMinutes;

    public GetTotalIssueCount(final JenkinsIntLogger logger, final PolarisService polarisService, final JobService jobService, final int jobTimeoutInMinutes) {
        this.logger = logger;
        this.polarisService = polarisService;
        this.jobService = jobService;
        this.jobTimeoutInMinutes = jobTimeoutInMinutes;
    }

    @Override
    public SubStepResponse<Integer> run(final SubStepResponse<? extends String> previousResponse) {
        if (previousResponse.isFailure() || !previousResponse.hasData()) {
            return SubStepResponse.FAILURE(previousResponse);
        }

        final PolarisCliResponseUtility polarisCliResponseUtility = PolarisCliResponseUtility.defaultUtility(logger);
        final String rawJson = previousResponse.getData();
        try {
            final CliCommonResponseModel polarisCliResponseModel = polarisCliResponseUtility.getPolarisCliResponseModelFromString(rawJson);

            final Optional<CommonIssueSummary> issueSummary = polarisCliResponseModel.getIssueSummary();
            final CommonScanInfo scanInfo = polarisCliResponseModel.getScanInfo();

            if (issueSummary.isPresent()) {
                logger.debug("Found total issue count in cli-scan.json, scan must have been run with -w");
                return SubStepResponse.SUCCESS(issueSummary.get().getTotalIssueCount());
            }

            if (jobTimeoutInMinutes < 0) {
                throw new PolarisIntegrationException(STEP_EXCEPTION_PREFIX + "Job timeout must be a positive number if the Polaris CLI is being run without -w");
            }

            final String issueApiUrl = Optional.ofNullable(scanInfo)
                                           .map(CommonScanInfo::getIssueApiUrl)
                                           .filter(StringUtils::isNotBlank)
                                           .orElseThrow(() -> new PolarisIntegrationException(
                                               "Synopsys Polaris for Jenkins cannot find the total issue count or issue api url in the cli-scan.json. Please ensure that you are using a supported version of the Polaris CLI."
                                           ));

            logger.debug("Found issue api url, polling for job status");

            for (final CommonToolInfo tool : polarisCliResponseModel.getTools()) {
                final String jobStatusUrl = tool.getJobStatusUrl();
                if (jobStatusUrl == null) {
                    throw new PolarisIntegrationException(STEP_EXCEPTION_PREFIX + "tool with name " + tool.getToolName() + " has no jobStatusUrl");
                }
                jobService.waitForJobStateIsCompletedOrDieByUrl(jobStatusUrl);
            }

            final CountV0Resources countV0Resources = polarisService.get(CountV0Resources.class, PolarisRequestFactory.createDefaultBuilder().uri(issueApiUrl).build());
            final Integer totalIssues = countV0Resources.getData().stream()
                                            .map(CountV0::getAttributes)
                                            .map(CountV0Attributes::getValue)
                                            .filter(Objects::nonNull)
                                            .reduce(0, Integer::sum);

            return SubStepResponse.SUCCESS(totalIssues);

        } catch (final InterruptedException | IntegrationException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return SubStepResponse.FAILURE(e);
        }
    }

}
