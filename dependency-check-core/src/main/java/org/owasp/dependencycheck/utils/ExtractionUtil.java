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
 * Copyright (c) 2013 Jeremy Long. All Rights Reserved.
 */
package org.owasp.dependencycheck.utils;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.utils.IOUtils;
import org.owasp.dependencycheck.Engine;
import org.owasp.dependencycheck.analyzer.exception.AnalysisException;
import org.owasp.dependencycheck.analyzer.exception.ArchiveExtractionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Set of utilities to extract files from archives.
 *
 * @author Jeremy Long
 */
public final class ExtractionUtil {

    /**
     * The logger.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(ExtractionUtil.class);

    /**
     * Private constructor for a utility class.
     */
    private ExtractionUtil() {
    }

    /**
     * Extracts the contents of an archive into the specified directory.
     *
     * @param archive an archive file such as a WAR or EAR
     * @param extractTo a directory to extract the contents to
     * @throws ExtractionException thrown if an exception occurs while extracting the files
     */
    public static void extractFiles(File archive, File extractTo) throws ExtractionException {
        extractFiles(archive, extractTo, null);
    }

    /**
     * Extracts the contents of an archive into the specified directory. The files are only extracted if they are supported by the
     * analyzers loaded into the specified engine. If the engine is specified as null then all files are extracted.
     *
     * @param archive an archive file such as a WAR or EAR
     * @param extractTo a directory to extract the contents to
     * @param engine the scanning engine
     * @throws ExtractionException thrown if there is an error extracting the files
     */
    public static void extractFiles(File archive, File extractTo, Engine engine) throws ExtractionException {
        if (archive == null || extractTo == null) {
            return;
        }

        FileInputStream fis = null;
        ZipInputStream zis = null;

        try {
            fis = new FileInputStream(archive);
        } catch (FileNotFoundException ex) {
            LOGGER.debug("", ex);
            throw new ExtractionException("Archive file was not found.", ex);
        }
        zis = new ZipInputStream(new BufferedInputStream(fis));
        ZipEntry entry;
        try {
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    final File d = new File(extractTo, entry.getName());
                    if (!d.exists() && !d.mkdirs()) {
                        final String msg = String.format("Unable to create '%s'.", d.getAbsolutePath());
                        throw new ExtractionException(msg);
                    }
                } else {
                    final File file = new File(extractTo, entry.getName());
                    if (engine == null || engine.accept(file)) {
                        FileOutputStream fos = null;
                        try {
                            fos = new FileOutputStream(file);
                            IOUtils.copy(zis, fos);
                        } catch (FileNotFoundException ex) {
                            LOGGER.debug("", ex);
                            final String msg = String.format("Unable to find file '%s'.", file.getName());
                            throw new ExtractionException(msg, ex);
                        } catch (IOException ex) {
                            LOGGER.debug("", ex);
                            final String msg = String.format("IO Exception while parsing file '%s'.", file.getName());
                            throw new ExtractionException(msg, ex);
                        } finally {
                            closeStream(fos);
                        }
                    }
                }
            }
        } catch (IOException ex) {
            final String msg = String.format("Exception reading archive '%s'.", archive.getName());
            LOGGER.debug("", ex);
            throw new ExtractionException(msg, ex);
        } finally {
            closeStream(zis);
        }
    }

    /**
     * Extracts the contents of an archive into the specified directory.
     *
     * @param archive an archive file such as a WAR or EAR
     * @param destination a directory to extract the contents to
     * @param filter determines which files get extracted
     * @throws ExtractionException thrown if the archive is not found
     */
    public static void extractFilesUsingFilter(File archive, File destination,
            FilenameFilter filter) throws ExtractionException {
        if (archive == null || destination == null) {
            return;
        }

        FileInputStream fis = null;
        try {
            fis = new FileInputStream(archive);
        } catch (FileNotFoundException ex) {
            LOGGER.debug("", ex);
            throw new ExtractionException("Archive file was not found.", ex);
        }
        try {
            extractArchive(new ZipArchiveInputStream(new BufferedInputStream(
                    fis)), destination, filter);
        } catch (ArchiveExtractionException ex) {
            LOGGER.warn("Exception extracting archive '{}'.", archive.getName());
            LOGGER.debug("", ex);
        } finally {
            try {
                fis.close();
            } catch (IOException ex) {
                LOGGER.debug("", ex);
            }
        }
    }

    /**
     * Extracts files from an archive.
     *
     * @param input the archive to extract files from
     * @param destination the location to write the files too
     * @param filter determines which files get extracted
     * @throws ArchiveExtractionException thrown if there is an exception extracting files from the archive
     */
    private static void extractArchive(ArchiveInputStream input,
            File destination, FilenameFilter filter)
            throws ArchiveExtractionException {
        ArchiveEntry entry;
        try {
            while ((entry = input.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    final File dir = new File(destination, entry.getName());
                    if (!dir.exists()) {
                        if (!dir.mkdirs()) {
                            final String msg = String.format(
                                    "Unable to create directory '%s'.",
                                    dir.getAbsolutePath());
                            throw new AnalysisException(msg);
                        }
                    }
                } else {
                    extractFile(input, destination, filter, entry);
                }
            }
        } catch (IOException ex) {
            throw new ArchiveExtractionException(ex);
        } catch (Throwable ex) {
            throw new ArchiveExtractionException(ex);
        } finally {
            closeStream(input);
        }
    }

    /**
     * Extracts a file from an archive (input stream) and correctly builds the directory structure.
     *
     * @param input the archive input stream
     * @param destination where to write the file
     * @param filter the file filter to apply to the files being extracted
     * @param entry the entry from the archive to extract
     * @throws ExtractionException thrown if there is an error reading from the archive stream
     */
    private static void extractFile(ArchiveInputStream input, File destination,
            FilenameFilter filter, ArchiveEntry entry) throws ExtractionException {
        final File file = new File(destination, entry.getName());
        if (filter.accept(file.getParentFile(), file.getName())) {
            LOGGER.debug("Extracting '{}'",
                    file.getPath());
            FileOutputStream fos = null;
            try {
                createParentFile(file);
                fos = new FileOutputStream(file);
                IOUtils.copy(input, fos);
            } catch (FileNotFoundException ex) {
                LOGGER.debug("", ex);
                final String msg = String.format("Unable to find file '%s'.",
                        file.getName());
                throw new ExtractionException(msg, ex);
            } catch (IOException ex) {
                LOGGER.debug("", ex);
                final String msg = String
                        .format("IO Exception while parsing file '%s'.",
                                file.getName());
                throw new ExtractionException(msg, ex);
            } finally {
                closeStream(fos);
            }
        }
    }

    /**
     * Closes the stream.
     *
     * @param stream the stream to close
     */
    private static void closeStream(Closeable stream) {
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException ex) {
                LOGGER.trace("", ex);
            }
        }
    }

    /**
     * Ensures the parent path is correctly created on disk so that the file can be extracted to the correct location.
     *
     * @param file the file path
     * @throws ExtractionException thrown if the parent paths could not be created
     */
    private static void createParentFile(final File file)
            throws ExtractionException {
        final File parent = file.getParentFile();
        if (!parent.isDirectory()) {
            if (!parent.mkdirs()) {
                final String msg = String.format(
                        "Unable to build directory '%s'.",
                        parent.getAbsolutePath());
                throw new ExtractionException(msg);
            }
        }
    }
}
