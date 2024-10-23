/*
 * blackduck-coverity-on-polaris
 *
 * Copyright ©2024 Black Duck Software, Inc. All rights reserved.
 * Black Duck® is a trademark of Black Duck Software, Inc. in the United States and other countries.
 */
package com.blackduck.integration.polaris.common.cli;

import com.blackduck.integration.exception.IntegrationException;
import com.blackduck.integration.log.IntLogger;
import com.blackduck.integration.polaris.common.configuration.OSArchTask;
import com.blackduck.integration.rest.HttpUrl;
import com.blackduck.integration.rest.client.IntHttpClient;
import com.blackduck.integration.rest.proxy.ProxyInfo;
import com.blackduck.integration.rest.request.Request;
import com.blackduck.integration.rest.response.Response;
import com.blackduck.integration.util.CleanupZipExpander;
import com.blackduck.integration.util.OperatingSystemType;
import com.google.gson.Gson;
import hudson.FilePath;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Optional;
import org.apache.commons.compress.archivers.ArchiveException;

public class PolarisDownloadUtility {
    public static final Integer DEFAULT_POLARIS_TIMEOUT = 120;

    public static final String LINUX_DOWNLOAD_URL_FORMAT = "/api/tools/%s_cli-linux64.zip";
    public static final String WINDOWS_DOWNLOAD_URL_FORMAT = "/api/tools/%s_cli-win64.zip";
    public static final String MAC_DOWNLOAD_URL_FORMAT = "/api/tools/%s_cli-macosx.zip";
    public static final String MAC_ARM_DOWNLOAD_URL_FORMAT = "/api/tools/%s_cli-macos_arm.zip";

    public static final String POLARIS_CLI_INSTALL_DIRECTORY = "Polaris_CLI_Installation";
    public static final String VERSION_FILENAME = "polarisVersion.txt";

    private final IntLogger logger;
    private final OperatingSystemType operatingSystemType;
    private final IntHttpClient intHttpClient;
    private final CleanupZipExpander cleanupZipExpander;
    private final HttpUrl polarisServerUrl;
    private final File installDirectory;

    public PolarisDownloadUtility(
            IntLogger logger,
            OperatingSystemType operatingSystemType,
            IntHttpClient intHttpClient,
            CleanupZipExpander cleanupZipExpander,
            HttpUrl polarisServerUrl,
            File downloadTargetDirectory) {
        if (null == polarisServerUrl) {
            throw new IllegalArgumentException("A Polaris server url must be provided.");
        }

        this.logger = logger;
        this.operatingSystemType = operatingSystemType;
        this.intHttpClient = intHttpClient;
        this.cleanupZipExpander = cleanupZipExpander;
        this.polarisServerUrl = polarisServerUrl;
        installDirectory = new File(downloadTargetDirectory, PolarisDownloadUtility.POLARIS_CLI_INSTALL_DIRECTORY);

        boolean dirCreated = installDirectory.mkdirs();
        if (!dirCreated && !installDirectory.exists()) {
            throw new IllegalArgumentException(
                    "Failed to create the install directory: " + installDirectory.getAbsolutePath());
        }

        if (!installDirectory.exists() || !installDirectory.isDirectory() || !installDirectory.canWrite()) {
            throw new IllegalArgumentException("The provided directory must exist and be writable.");
        }
    }

    public static PolarisDownloadUtility defaultUtility(
            IntLogger logger, HttpUrl polarisServerUrl, ProxyInfo proxyInfo, File downloadTargetDirectory) {
        OperatingSystemType operatingSystemType = OperatingSystemType.determineFromSystem();
        IntHttpClient intHttpClient =
                new IntHttpClient(logger, new Gson(), PolarisDownloadUtility.DEFAULT_POLARIS_TIMEOUT, false, proxyInfo);
        CleanupZipExpander cleanupZipExpander = new CleanupZipExpander(logger);
        return new PolarisDownloadUtility(
                logger,
                operatingSystemType,
                intHttpClient,
                cleanupZipExpander,
                polarisServerUrl,
                downloadTargetDirectory);
    }

    public static PolarisDownloadUtility defaultUtilityNoProxy(
            IntLogger logger, HttpUrl polarisServerUrl, File downloadTargetDirectory) {
        return defaultUtility(logger, polarisServerUrl, ProxyInfo.NO_PROXY_INFO, downloadTargetDirectory);
    }

    /**
     * The Coverity on Polaris CLI will be downloaded if it has not previously been downloaded or
     * if it has been updated on the server. The absolute path to the swip_cli
     * executable will be returned if it was downloaded or found successfully,
     * otherwise an Optional.empty will be returned and the log will contain
     * details concerning the failure.
     */
    public Optional<String> getOrDownloadPolarisCliExecutable() throws IntegrationException {
        File binDirectory = getOrDownloadPolarisCliBin().orElse(null);

        if (binDirectory != null && binDirectory.exists() && binDirectory.isDirectory()) {
            try {
                File polarisCliExecutable = getPolarisCli(binDirectory);
                logger.info("Coverity on Polaris CLI downloaded/found successfully: "
                        + polarisCliExecutable.getCanonicalPath());
                return Optional.of(polarisCliExecutable.getCanonicalPath());
            } catch (Exception e) {
                logger.error("The Coverity on Polaris CLI executable could not be found: " + e.getMessage());
            }
        }

        return Optional.empty();
    }

    public Optional<File> getOrDownloadPolarisCliBin() throws IntegrationException {
        File versionFile;
        try {
            versionFile = getOrCreateVersionFile();
        } catch (IOException e) {
            logger.error("Could not create the version file: " + e.getMessage());
            return Optional.empty();
        }

        String downloadUrlFormat = getDownloadUrlFormat();
        return getOrDownloadPolarisCliBin(versionFile, downloadUrlFormat);
    }

    public Optional<File> getOrDownloadPolarisCliBin(File versionFile, String downloadUrlFormat) {
        File binDirectory = null;
        try {
            binDirectory = downloadIfModified(versionFile, downloadUrlFormat);
        } catch (Exception e) {
            logger.error("The Coverity on Polaris CLI could not be downloaded successfully: " + e.getMessage());
        }

        return Optional.ofNullable(binDirectory);
    }

    public Optional<String> getOrDownloadPolarisCliHome() throws IntegrationException {
        return getOrDownloadPolarisCliBin().map(File::getParentFile).flatMap(file -> {
            String pathToPolarisCliHome = null;

            try {
                pathToPolarisCliHome = file.getCanonicalPath();
            } catch (IOException e) {
                logger.error("The Coverity on Polaris CLI home could not be found: " + e.getMessage());
            }

            return Optional.ofNullable(pathToPolarisCliHome);
        });
    }

    public File getOrCreateVersionFile() throws IOException {
        File versionFile = new File(installDirectory, PolarisDownloadUtility.VERSION_FILENAME);
        if (!versionFile.exists()) {
            logger.info("The version file has not been created yet so creating it now.");

            boolean fileCreated = versionFile.createNewFile();
            if (!fileCreated) {
                throw new IOException("Failed to create the version file: " + versionFile.getAbsolutePath());
            }

            boolean lastModified = versionFile.setLastModified(0L);
            if (!lastModified) {
                throw new IOException("Failed to set last modified: " + versionFile.getAbsolutePath());
            }
        }

        return versionFile;
    }

    public String getDownloadUrlFormat() throws IntegrationException {
        if (OperatingSystemType.MAC == operatingSystemType) {
            // If the OS Architecture is Mac non-ARM, the return tool name as "%s_cli-macosx.zip"
            // If the OS Architecture is Mac ARM architecture, the return tool name as "%s_cli-macos_arm.zip"
            FilePath workspace = new FilePath(new File(installDirectory.getPath()));
            String arch = getAgentOsArch(workspace);
            if (arch != null) {
                if (arch.startsWith("arm") || arch.startsWith("aarch")) {
                    return polarisServerUrl + PolarisDownloadUtility.MAC_ARM_DOWNLOAD_URL_FORMAT;
                } else {
                    return polarisServerUrl + PolarisDownloadUtility.MAC_DOWNLOAD_URL_FORMAT;
                }
            } else {
                throw new IntegrationException("OS architecture of MAC could not be determined. 'arch' is null.");
            }
        } else if (OperatingSystemType.WINDOWS == operatingSystemType) {
            return polarisServerUrl + PolarisDownloadUtility.WINDOWS_DOWNLOAD_URL_FORMAT;
        } else {
            return polarisServerUrl + PolarisDownloadUtility.LINUX_DOWNLOAD_URL_FORMAT;
        }
    }

    private String getAgentOsArch(FilePath workspace) {
        String arch = null;

        if (workspace.isRemote()) {
            try {
                arch = workspace.act(new OSArchTask());
            } catch (IOException | InterruptedException e) {
                logger.error("An exception occurred while fetching OS architecture information for the agent node: "
                        + e.getMessage());
                Thread.currentThread().interrupt();
            }
        } else {
            arch = System.getProperty("os.arch").toLowerCase();
        }

        return arch;
    }

    private File downloadIfModified(File versionFile, String downloadUrlFormat)
            throws IOException, IntegrationException, ArchiveException {
        long lastTimeDownloaded = versionFile.lastModified();
        logger.debug(String.format("last time downloaded: %d", lastTimeDownloaded));

        HttpUrl swipDownloadUrl = new HttpUrl(String.format(downloadUrlFormat, "swip"));
        Request swipDownloadRequest = new Request.Builder(swipDownloadUrl).build();
        try (Response downloadResponse = intHttpClient.execute(swipDownloadRequest)) {
            if (!downloadResponse.isStatusCodeError()) {
                return getBinDirectoryFromResponse(downloadResponse, versionFile, lastTimeDownloaded);
            }
        }

        HttpUrl polarisDownloadUrl = new HttpUrl(String.format(downloadUrlFormat, "polaris"));
        Request polarisDownloadRequest = new Request.Builder(polarisDownloadUrl).build();
        try (Response downloadResponse = intHttpClient.execute(polarisDownloadRequest)) {
            if (!downloadResponse.isStatusCodeError()) {
                return getBinDirectoryFromResponse(downloadResponse, versionFile, lastTimeDownloaded);
            }
        }

        return getBinDirectory();
    }

    private File getBinDirectoryFromResponse(Response response, File versionFile, long lastTimeDownloaded)
            throws IOException, IntegrationException, ArchiveException {
        long lastModifiedOnServer = response.getLastModified();
        if (lastModifiedOnServer == lastTimeDownloaded) {
            logger.debug(
                    "The Coverity on Polaris CLI has not been modified since it was last downloaded - skipping download.");
            return getBinDirectory();
        } else {
            logger.info("Downloading the Coverity on Polaris CLI.");
            try (InputStream responseStream = response.getContent()) {
                cleanupZipExpander.expand(responseStream, installDirectory);
            }

            boolean lastModified = versionFile.setLastModified(lastModifiedOnServer);
            if (!lastModified) {
                throw new IOException("Failed to set last modified: " + versionFile.getAbsolutePath());
            }

            File binDirectory = getBinDirectory();
            makeBinFilesExecutable(binDirectory);

            logger.info("Coverity on Polaris CLI downloaded successfully.");

            return binDirectory;
        }
    }

    // since we know that we only allow a single directory in installDirectory,
    // that single directory IS the expanded archive
    private File getBinDirectory() throws IntegrationException {
        File[] directories = installDirectory.listFiles(File::isDirectory);
        if (directories == null || directories.length == 0) {
            throw new IntegrationException(String.format(
                    "The %s directory is empty, so the Coverity on Polaris CLI can not be run.",
                    PolarisDownloadUtility.POLARIS_CLI_INSTALL_DIRECTORY));
        }

        if (directories.length > 1) {
            throw new IntegrationException(String.format(
                    "The %s directory should only be modified by polaris-common. Please delete all files from that directory and try again.",
                    PolarisDownloadUtility.POLARIS_CLI_INSTALL_DIRECTORY));
        }

        File polarisCliDirectory = directories[0];
        File bin = new File(polarisCliDirectory, "bin");

        return bin;
    }

    private void makeBinFilesExecutable(File binDirectory) {
        Arrays.stream(binDirectory.listFiles()).forEach(file -> file.setExecutable(true));
    }

    private File getPolarisCli(File binDirectory) throws IntegrationException {
        Optional<File> polarisCli = checkFile(binDirectory, "polaris");
        Optional<File> swipCli = checkFile(binDirectory, "swip_cli");

        if (polarisCli.isPresent()) {
            return polarisCli.get();
        } else if (swipCli.isPresent()) {
            return swipCli.get();
        }

        throw new IntegrationException(
                "The Coverity on Polaris CLI does not appear to have been downloaded correctly - be sure to download it first.");
    }

    private Optional<File> checkFile(File binDirectory, String filePrefix) {
        String filename = filePrefix;
        if (OperatingSystemType.WINDOWS == operatingSystemType) {
            filename += ".exe";
        }
        File file = new File(binDirectory, filename);

        if (file.exists() && file.isFile() && file.length() > 0L) {
            return Optional.of(file);
        } else {
            return Optional.empty();
        }
    }
}
