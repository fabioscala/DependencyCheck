/*
 * This file is part of dependency-check-core.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Copyright (c) 2014 Jeremy Long. All Rights Reserved.
 */
package org.owasp.dependencycheck.analyzer;

import org.apache.commons.io.FileUtils;
import org.owasp.dependencycheck.Engine;
import org.owasp.dependencycheck.analyzer.exception.AnalysisException;
import org.owasp.dependencycheck.data.nexus.MavenArtifact;
import org.owasp.dependencycheck.data.nexus.NexusSearch;
import org.owasp.dependencycheck.dependency.Confidence;
import org.owasp.dependencycheck.dependency.Dependency;
import org.owasp.dependencycheck.dependency.Evidence;
import org.owasp.dependencycheck.xml.pom.PomUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileFilter;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import org.owasp.dependencycheck.utils.DownloadFailedException;
import org.owasp.dependencycheck.utils.Downloader;
import org.owasp.dependencycheck.utils.FileFilterBuilder;
import org.owasp.dependencycheck.utils.InvalidSettingException;
import org.owasp.dependencycheck.utils.Settings;

/**
 * Analyzer which will attempt to locate a dependency on a Nexus service by SHA-1 digest of the dependency.
 *
 * There are two settings which govern this behavior:
 *
 * <ul>
 * <li>{@link org.owasp.dependencycheck.utils.Settings.KEYS#ANALYZER_NEXUS_ENABLED} determines whether this analyzer is even
 * enabled. This can be overridden by setting the system property.</li>
 * <li>{@link org.owasp.dependencycheck.utils.Settings.KEYS#ANALYZER_NEXUS_URL} the URL to a Nexus service to search by SHA-1.
 * There is an expected <code>%s</code> in this where the SHA-1 will get entered.</li>
 * </ul>
 *
 * @author colezlaw
 */
public class NexusAnalyzer extends AbstractFileTypeAnalyzer {

    /**
     * The default URL - this will be used by the CentralAnalyzer to determine whether to enable this.
     */
    public static final String DEFAULT_URL = "https://repository.sonatype.org/service/local/";

    /**
     * The logger.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(NexusAnalyzer.class);

    /**
     * The name of the analyzer.
     */
    private static final String ANALYZER_NAME = "Nexus Analyzer";

    /**
     * The phase in which the analyzer runs.
     */
    private static final AnalysisPhase ANALYSIS_PHASE = AnalysisPhase.INFORMATION_COLLECTION;

    /**
     * The types of files on which this will work.
     */
    private static final String SUPPORTED_EXTENSIONS = "jar";

    /**
     * The Nexus Search to be set up for this analyzer.
     */
    private NexusSearch searcher;

    /**
     * Field indicating if the analyzer is enabled.
     */
    private final boolean enabled = checkEnabled();

    /**
     * Determines if this analyzer is enabled
     *
     * @return <code>true</code> if the analyzer is enabled; otherwise <code>false</code>
     */
    private boolean checkEnabled() {
        /* Enable this analyzer ONLY if the Nexus URL has been set to something
         other than the default one (if it's the default one, we'll use the
         central one) and it's enabled by the user.
         */
        boolean retval = false;
        try {
            if ((!DEFAULT_URL.equals(Settings.getString(Settings.KEYS.ANALYZER_NEXUS_URL)))
                    && Settings.getBoolean(Settings.KEYS.ANALYZER_NEXUS_ENABLED)) {
                LOGGER.info("Enabling Nexus analyzer");
                retval = true;
            } else {
                LOGGER.debug("Nexus analyzer disabled, using Central instead");
            }
        } catch (InvalidSettingException ise) {
            LOGGER.warn("Invalid setting. Disabling Nexus analyzer");
        }

        return retval;
    }

    /**
     * Determine whether to enable this analyzer or not.
     *
     * @return whether the analyzer should be enabled
     */
    @Override
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Initializes the analyzer once before any analysis is performed.
     *
     * @throws Exception if there's an error during initialization
     */
    @Override
    public void initializeFileTypeAnalyzer() throws Exception {
        LOGGER.debug("Initializing Nexus Analyzer");
        LOGGER.debug("Nexus Analyzer enabled: {}", isEnabled());
        if (isEnabled()) {
            final String searchUrl = Settings.getString(Settings.KEYS.ANALYZER_NEXUS_URL);
            LOGGER.debug("Nexus Analyzer URL: {}", searchUrl);
            try {
                searcher = new NexusSearch(new URL(searchUrl));
                if (!searcher.preflightRequest()) {
                    LOGGER.warn("There was an issue getting Nexus status. Disabling analyzer.");
                    setEnabled(false);
                }
            } catch (MalformedURLException mue) {
                // I know that initialize can throw an exception, but we'll
                // just disable the analyzer if the URL isn't valid
                LOGGER.warn("Property {} not a valid URL. Nexus Analyzer disabled", searchUrl);
                setEnabled(false);
            }
        }
    }

    /**
     * Returns the analyzer's name.
     *
     * @return the name of the analyzer
     */
    @Override
    public String getName() {
        return ANALYZER_NAME;
    }

    /**
     * Returns the key used in the properties file to reference the analyzer's enabled property.
     *
     * @return the analyzer's enabled property setting key
     */
    @Override
    protected String getAnalyzerEnabledSettingKey() {
        return Settings.KEYS.ANALYZER_NEXUS_ENABLED;
    }

    /**
     * Returns the analysis phase under which the analyzer runs.
     *
     * @return the phase under which this analyzer runs
     */
    @Override
    public AnalysisPhase getAnalysisPhase() {
        return ANALYSIS_PHASE;
    }

    /**
     * The file filter used to determine which files this analyzer supports.
     */
    private static final FileFilter FILTER = FileFilterBuilder.newInstance().addExtensions(SUPPORTED_EXTENSIONS).build();

    /**
     * Returns the FileFilter
     *
     * @return the FileFilter
     */
    @Override
    protected FileFilter getFileFilter() {
        return FILTER;
    }

    /**
     * Performs the analysis.
     *
     * @param dependency the dependency to analyze
     * @param engine the engine
     * @throws AnalysisException when there's an exception during analysis
     */
    @Override
    public void analyzeFileType(Dependency dependency, Engine engine) throws AnalysisException {
        if (!isEnabled()) {
            return;
        }
        try {
            final MavenArtifact ma = searcher.searchSha1(dependency.getSha1sum());
            dependency.addAsEvidence("nexus", ma, Confidence.HIGH);
            boolean pomAnalyzed = false;
            LOGGER.debug("POM URL {}", ma.getPomUrl());
            for (Evidence e : dependency.getVendorEvidence()) {
                if ("pom".equals(e.getSource())) {
                    pomAnalyzed = true;
                    break;
                }
            }
            if (!pomAnalyzed && ma.getPomUrl() != null) {
                File pomFile = null;
                try {
                    final File baseDir = Settings.getTempDirectory();
                    pomFile = File.createTempFile("pom", ".xml", baseDir);
                    if (!pomFile.delete()) {
                        LOGGER.warn("Unable to fetch pom.xml for {} from Nexus repository; "
                                + "this could result in undetected CPE/CVEs.", dependency.getFileName());
                        LOGGER.debug("Unable to delete temp file");
                    }
                    LOGGER.debug("Downloading {}", ma.getPomUrl());
                    Downloader.fetchFile(new URL(ma.getPomUrl()), pomFile);
                    PomUtils.analyzePOM(dependency, pomFile);
                } catch (DownloadFailedException ex) {
                    LOGGER.warn("Unable to download pom.xml for {} from Nexus repository; "
                            + "this could result in undetected CPE/CVEs.", dependency.getFileName());
                } finally {
                    if (pomFile != null && !FileUtils.deleteQuietly(pomFile)) {
                        pomFile.deleteOnExit();
                    }
                }
            }
        } catch (IllegalArgumentException iae) {
            //dependency.addAnalysisException(new AnalysisException("Invalid SHA-1"));
            LOGGER.info("invalid sha-1 hash on {}", dependency.getFileName());
        } catch (FileNotFoundException fnfe) {
            //dependency.addAnalysisException(new AnalysisException("Artifact not found on repository"));
            LOGGER.debug("Artifact not found in repository '{}'", dependency.getFileName());
            LOGGER.debug(fnfe.getMessage(), fnfe);
        } catch (IOException ioe) {
            //dependency.addAnalysisException(new AnalysisException("Could not connect to repository", ioe));
            LOGGER.debug("Could not connect to nexus repository", ioe);
        }
    }
}
