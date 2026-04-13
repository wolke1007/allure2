/*
 *  Copyright 2016-2026 Qameta Software Inc
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package io.qameta.allure;

import io.qameta.allure.core.Configuration;
import io.qameta.allure.core.FileSystemReportStorage;
import io.qameta.allure.core.InMemoryReportStorage;
import io.qameta.allure.core.LaunchResults;
import io.qameta.allure.core.ReportWebGenerator;
import io.qameta.allure.core.TestsResultsPlugin;
import io.qameta.allure.entity.TestResult;
import io.qameta.allure.util.DeleteVisitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * @author charlie (Dmitry Baev).
 */
public class ReportGenerator {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReportGenerator.class);

    private final Configuration configuration;

    public ReportGenerator(final Configuration configuration) {
        this.configuration = configuration;
    }

    private LaunchResults readResults(final Path resultsDirectory) {
        final DefaultResultsVisitor visitor = new DefaultResultsVisitor(configuration);
        configuration.getExtensions(Reader.class)
                .forEach(reader -> reader.readResults(configuration, visitor, resultsDirectory));
        return visitor.getLaunchResults();
    }

    private void aggregate(final List<LaunchResults> results, final ReportStorage storage) {
        processOldAggregators(results, storage);

        for (final Aggregator2 aggregator : configuration.getExtensions(Aggregator2.class)) {
            aggregator.aggregate(configuration, results, storage);
        }
    }

    @SuppressWarnings("deprecation")
    private void processOldAggregators(final List<LaunchResults> results,
                                       final ReportStorage storage) {
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("allure-");

            for (Aggregator aggregator : configuration.getExtensions(Aggregator.class)) {
                aggregator.aggregate(configuration, results, tempDir);
            }

            final Path finalTempDir = tempDir;
            Files.walkFileTree(tempDir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(final Path file,
                                                 final BasicFileAttributes attrs) {
                    final String fileId = file.relativize(finalTempDir).toString();
                    storage.addDataFile(fileId, file);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            if (Objects.nonNull(tempDir)) {
                try {
                    Files.walkFileTree(tempDir, new DeleteVisitor());
                } catch (IOException ignored) {
                    // do nothing
                }
            }
        }

    }

    public void generate(final Path outputDirectory, final List<Path> resultsDirectories) {
        generate(
                new FileSystemReportStorage(outputDirectory),
                outputDirectory,
                resultsDirectories
        );
    }

    public void generate(final Path outputDirectory, final Path... resultsDirectories) {
        generate(outputDirectory, Arrays.asList(resultsDirectories));
    }

    private void generate(final ReportStorage storage,
                          final Path outputDirectory,
                          final List<Path> resultsDirectories) {
        if (isStreamingModeEnabled()) {
            generateStreaming(storage, resultsDirectories);
        } else {
            final List<LaunchResults> results = resultsDirectories.stream()
                    .filter(this::isValidResultsDirectory)
                    .map(this::readResults)
                    .collect(Collectors.toList());
            aggregate(results, storage);
        }
        new ReportWebGenerator().generate(configuration, storage, outputDirectory);
    }

    /**
     * Returns true when streaming generate mode is enabled via system property
     * {@code -Dallure.streaming.generate=true} or env var {@code ALLURE_STREAMING_GENERATE=true}.
     *
     * <p>Streaming mode writes each test-case JSON file immediately after conversion instead of
     * accumulating all results in memory. This reduces peak heap usage from O(all results) to
     * O(parallelism * one result), at the cost of a slightly more complex code path.
     */
    private boolean isStreamingModeEnabled() {
        final String prop = System.getProperty("allure.streaming.generate");
        if (prop != null) {
            return Boolean.parseBoolean(prop);
        }
        return Boolean.parseBoolean(
                System.getenv().getOrDefault("ALLURE_STREAMING_GENERATE", "false")
        );
    }

    /**
     * Streaming generate: writes {@code data/test-cases/*.json} one-by-one during the read
     * phase, then runs all aggregators (except {@link TestsResultsPlugin}) with lightweight
     * stripped results that have no step/attachment trees.
     */
    private void generateStreaming(final ReportStorage storage,
                                   final List<Path> resultsDirectories) {
        final List<LaunchResults> launchMetadata = new ArrayList<>();
        resultsDirectories.stream()
                .filter(this::isValidResultsDirectory)
                .forEach(dir -> launchMetadata.add(streamingReadDir(dir, storage)));
        aggregateStreaming(launchMetadata, storage);
    }

    /**
     * Reads one results directory in streaming mode: for each converted TestResult the full
     * JSON is written to storage immediately, then the step tree is cleared so the GC can
     * reclaim it before the next result is processed.
     *
     * <p>The returned {@link LaunchResults} contains stripped TestResult objects (no
     * {@code testStage}/{@code beforeStages}/{@code afterStages}) suitable for summary,
     * history, and category aggregators that only need metadata.
     */
    private LaunchResults streamingReadDir(final Path resultsDirectory,
                                           final ReportStorage storage) {
        final Set<TestResult> strippedResults = ConcurrentHashMap.newKeySet();

        final DefaultResultsVisitor visitor = new DefaultResultsVisitor(configuration,
                testResult -> {
                    // Write the full JSON (with step trees) to disk immediately
                    storage.addDataJson(
                            String.format("data/test-cases/%s", testResult.getSource()),
                            testResult
                    );
                    // Drop the step/attachment trees so they can be GC'd
                    testResult.setTestStage(null);
                    testResult.getBeforeStages().clear();
                    testResult.getAfterStages().clear();
                    // Keep the lightweight metadata for subsequent aggregators
                    strippedResults.add(testResult);
                }
        );

        configuration.getExtensions(Reader.class)
                .forEach(reader -> reader.readResults(configuration, visitor, resultsDirectory));

        return visitor.getLaunchResults(strippedResults);
    }

    /**
     * Runs all aggregators in streaming mode, skipping {@link TestsResultsPlugin} because
     * the test-case files were already written during {@link #streamingReadDir}.
     */
    private void aggregateStreaming(final List<LaunchResults> launchMetadata,
                                    final ReportStorage storage) {
        processOldAggregators(launchMetadata, storage);
        for (final Aggregator2 aggregator : configuration.getExtensions(Aggregator2.class)) {
            if (aggregator instanceof TestsResultsPlugin) {
                // Already handled: each test-case JSON was written during the streaming read phase.
                // Skipping here prevents overwriting the full results with stripped ones.
                continue;
            }
            aggregator.aggregate(configuration, launchMetadata, storage);
        }
    }

    public void generateSingleFile(final Path outputDirectory, final List<Path> resultsDirectories) {
        final InMemoryReportStorage storage = new InMemoryReportStorage();
        generate(storage, outputDirectory, resultsDirectories);
    }

    private boolean isValidResultsDirectory(final Path resultsDirectory) {
        if (Files.notExists(resultsDirectory)) {
            LOGGER.warn("{} does not exist", resultsDirectory);
            return false;
        }
        if (!Files.isDirectory(resultsDirectory)) {
            LOGGER.warn("{} is not a directory", resultsDirectory);
            return false;
        }
        return true;
    }
}
