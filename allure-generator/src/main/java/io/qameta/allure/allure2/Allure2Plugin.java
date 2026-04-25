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
package io.qameta.allure.allure2;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.qameta.allure.Reader;
import io.qameta.allure.context.RandomUidContext;
import io.qameta.allure.core.Configuration;
import io.qameta.allure.core.ResultsVisitor;
import io.qameta.allure.entity.Attachment;
import io.qameta.allure.entity.Label;
import io.qameta.allure.entity.Link;
import io.qameta.allure.entity.Parameter;
import io.qameta.allure.entity.StageResult;
import io.qameta.allure.entity.Status;
import io.qameta.allure.entity.Step;
import io.qameta.allure.entity.Time;
import io.qameta.allure.model.FixtureResult;
import io.qameta.allure.model.StepResult;
import io.qameta.allure.model.TestResult;
import io.qameta.allure.model.TestResultContainer;
import io.qameta.allure.util.HtmlSanitizerUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static io.qameta.allure.detect.WellKnownFileExtensionsUtils.getExtensionByMimeType;
import static io.qameta.allure.entity.LabelName.RESULT_FORMAT;
import static io.qameta.allure.model.Parameter.Mode.HIDDEN;
import static io.qameta.allure.model.Parameter.Mode.MASKED;
import static io.qameta.allure.util.ConvertUtils.convertList;
import static java.nio.file.Files.newDirectoryStream;
import static java.util.Comparator.comparing;
import static java.util.Comparator.naturalOrder;
import static java.util.Comparator.nullsFirst;
import static java.util.Comparator.nullsLast;
import static java.util.Objects.nonNull;

/**
 * Plugin that reads results from Allure 2 data format.
 *
 * @since 2.0
 */
@SuppressWarnings({
        "ClassDataAbstractionCoupling",
        "ClassFanOutComplexity",
        "PMD.TooManyMethods",
})
public class Allure2Plugin implements Reader {

    @SuppressWarnings("WeakerAccess")
    public static final String ALLURE2_RESULTS_FORMAT = "allure2";

    private static final Logger LOGGER = LoggerFactory.getLogger(Allure2Plugin.class);

    private static final Pattern ATTACHMENT_SOURCE_PATTERN = Pattern.compile("^[a-zA-Z0-9._-]{1,100}$");

    private static final Comparator<StageResult> BY_START = comparing(
            StageResult::getTime,
            nullsLast(comparing(Time::getStart, nullsLast(naturalOrder())))
    );

    private static final Comparator<Parameter> PARAMETER_COMPARATOR =
            comparing(Parameter::getName, nullsFirst(naturalOrder()))
                    .thenComparing(Parameter::getValue, nullsFirst(naturalOrder()));

    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(MapperFeature.USE_WRAPPER_NAME_AS_PROPERTY_NAME)
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
            .enable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DeserializationFeature.FAIL_ON_IGNORED_PROPERTIES)
            .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .build();

    @Override
    public void readResults(final Configuration configuration,
                            final ResultsVisitor visitor,
                            final Path resultsDirectory) {
        final RandomUidContext context = configuration.requireContext(RandomUidContext.class);
        final boolean sequential = visitor.isSequential();

        if (sequential) {
            // Sequential streaming mode (two phases, avoids OOM from loading all containers at once)
            //   Phase 1: Scan all containers with streaming JsonParser, read only the children list,
            //            skip large befores/afters arrays. Build test_uuid → container_paths mapping.
            //   Phase 2: Process one result at a time, lazily read the corresponding containers for
            //            fixture stages, discard when done; rely on System.gc() to reclaim large strings.
            // peak heap = one result + one container (not all containers accumulated)
            final Map<String, List<Path>> testToContainers = scanContainerChildren(resultsDirectory);

            final List<Path> resultFiles = listFiles(resultsDirectory, "*-result.json")
                    .collect(Collectors.toList());
            LOGGER.info("Sequential streaming: {} result files to process", resultFiles.size());
            int idx = 0;
            for (final Path file : resultFiles) {
                idx++;
                final long sz = sizeSafe(file);
                if (sz > 10 * 1024 * 1024) {
                    final Runtime r = Runtime.getRuntime();
                    LOGGER.info("Processing large file [{}/{}] {} ({}MB), heap used={}MB / max={}MB",
                            idx, resultFiles.size(), file.getFileName(),
                            sz / 1024 / 1024,
                            (r.totalMemory() - r.freeMemory()) / 1024 / 1024,
                            r.maxMemory() / 1024 / 1024);
                }
                processSingleFile(file, context.getValue(), resultsDirectory, visitor,
                        testToContainers);
                System.gc();
            }
        } else {
            // Parallel mode (original logic, loads all containers at once)
            final Map<String, List<StageResult>> befores = new ConcurrentHashMap<>();
            final Map<String, List<StageResult>> afters = new ConcurrentHashMap<>();

            readTestResultsContainers(resultsDirectory, false)
                    .filter(group -> !Objects.isNull(group.getChildren()))
                    .forEach(group -> {
                        processStages(visitor, resultsDirectory, group, befores,
                                group.getBefores(), false);
                        processStages(visitor, resultsDirectory, group, afters,
                                group.getAfters(), false);
                    });

            sortByStart(befores);
            sortByStart(afters);

            readTestResults(resultsDirectory, false)
                    .forEach(result -> convert(
                            context.getValue(),
                            resultsDirectory, visitor,
                            result,
                            befores, afters
                    ));
        }
    }

    private static void sortByStart(final Map<String, List<StageResult>> befores) {
        befores.keySet().forEach(key -> befores.compute(key, (s, stageResults) -> {
            if (Objects.isNull(stageResults)) {
                return null;
            }
            final List<StageResult> res = new ArrayList<>(stageResults);
            res.sort(BY_START);
            return res;
        }));
    }

    private void processStages(final ResultsVisitor visitor,
                               final Path resultsDirectory,
                               final TestResultContainer group,
                               final Map<String, List<StageResult>> befores,
                               final List<FixtureResult> fixtureResults,
                               final boolean sequential) {
        if (Objects.isNull(fixtureResults)) {
            return;
        }

        final List<StageResult> stages = fixtureResults.stream()
                .map(fixtureResult -> convert(resultsDirectory, visitor, fixtureResult))
                .collect(Collectors.toList());

        final Set<String> visited = ConcurrentHashMap.newKeySet();

        final Stream<String> children = sequential
                ? group.getChildren().stream()
                : group.getChildren().parallelStream();
        children
                .filter(Objects::nonNull)
                .filter(visited::add)
                .forEach(child -> befores.compute(child, (s, stageResults) -> {
                    if (Objects.isNull(stageResults)) {
                        return new LinkedList<>(stages);
                    }
                    stageResults.addAll(stages);
                    return stageResults;
                }));
    }

    // Processes a single result file in sequential mode:
    //   1. Deserialize the result
    //   2. Find the corresponding container files by result.uuid
    //   3. Lazily read those containers (each is discarded after use)
    //   4. Convert container fixtures to stages and put them in per-result maps
    //   5. Call convert() to finalize and write to disk
    // After the method returns, all locals (result/container/maps/stages) are unreachable,
    // allowing the outer System.gc() to effectively reclaim large strings.
    private void processSingleFile(final Path file,
                                   final Supplier<String> uidGenerator,
                                   final Path resultsDirectory,
                                   final ResultsVisitor visitor,
                                   final Map<String, List<Path>> testToContainers) {
        final Optional<TestResult> opt = readTestResult(file);
        if (!opt.isPresent()) {
            return;
        }
        final TestResult result = opt.get();

        // Temporary maps scoped to the current result only (not accumulated across results)
        final Map<String, List<StageResult>> befores = new HashMap<>();
        final Map<String, List<StageResult>> afters = new HashMap<>();

        final String testUuid = result.getUuid();
        if (testUuid != null) {
            final List<Path> containerPaths = testToContainers.get(testUuid);
            if (containerPaths != null) {
                for (final Path containerPath : containerPaths) {
                    loadStagesFromContainer(containerPath, testUuid, visitor, resultsDirectory,
                            befores, afters);
                }
                sortByStart(befores);
                sortByStart(afters);
            }
        }

        convert(uidGenerator, resultsDirectory, visitor, result, befores, afters);
    }

    // Fully deserializes a single container, converts its befores/afters fixtures to stages,
    // and adds them to the current result's temporary maps. The container is unreachable after return.
    private void loadStagesFromContainer(final Path containerPath,
                                          final String testUuid,
                                          final ResultsVisitor visitor,
                                          final Path resultsDirectory,
                                          final Map<String, List<StageResult>> befores,
                                          final Map<String, List<StageResult>> afters) {
        final Optional<TestResultContainer> cOpt = readTestResultContainer(containerPath);
        if (!cOpt.isPresent()) {
            return;
        }
        final TestResultContainer container = cOpt.get();
        if (container.getBefores() != null && !container.getBefores().isEmpty()) {
            final List<StageResult> stages = container.getBefores().stream()
                    .map(fr -> convert(resultsDirectory, visitor, fr))
                    .collect(Collectors.toList());
            befores.computeIfAbsent(testUuid, k -> new LinkedList<>()).addAll(stages);
        }
        if (container.getAfters() != null && !container.getAfters().isEmpty()) {
            final List<StageResult> stages = container.getAfters().stream()
                    .map(fr -> convert(resultsDirectory, visitor, fr))
                    .collect(Collectors.toList());
            afters.computeIfAbsent(testUuid, k -> new LinkedList<>()).addAll(stages);
        }
    }

    // Phase 1: Scan all containers with streaming JsonParser, reading only the children list,
    // skipping large befores/afters arrays. Memory cost is O(total children), independent of fixture size.
    private Map<String, List<Path>> scanContainerChildren(final Path resultsDirectory) {
        final Map<String, List<Path>> testToContainers = new HashMap<>();
        final List<Path> containerFiles = listFiles(resultsDirectory, "*-container.json")
                .collect(Collectors.toList());
        LOGGER.info("Scanning {} container files for children mapping (streaming, "
                + "skipping befores/afters bodies)", containerFiles.size());
        for (final Path containerFile : containerFiles) {
            for (final String childUuid : readContainerChildrenStreaming(containerFile)) {
                testToContainers.computeIfAbsent(childUuid, k -> new ArrayList<>())
                        .add(containerFile);
            }
        }
        LOGGER.info("Container scan complete: {} test UUIDs mapped",
                testToContainers.size());
        return testToContainers;
    }

    private static final JsonFactory STREAM_JSON_FACTORY = new JsonFactory();

    private static List<String> readContainerChildrenStreaming(final Path file) {
        final List<String> children = new ArrayList<>();
        try (JsonParser parser = STREAM_JSON_FACTORY.createParser(file.toFile())) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                return children;
            }
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                final String fieldName = parser.currentName();
                parser.nextToken();
                if ("children".equalsIgnoreCase(fieldName)
                        && parser.currentToken() == JsonToken.START_ARRAY) {
                    while (parser.nextToken() != JsonToken.END_ARRAY) {
                        if (parser.currentToken() == JsonToken.VALUE_STRING) {
                            children.add(parser.getText());
                        }
                    }
                } else {
                    parser.skipChildren();
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Could not scan container {} for children: {}", file, e.getMessage());
        }
        return children;
    }

    private void convert(final Supplier<String> uidGenerator,
                         final Path resultsDirectory,
                         final ResultsVisitor visitor,
                         final TestResult result,
                         final Map<String, List<StageResult>> befores,
                         final Map<String, List<StageResult>> afters) {
        final io.qameta.allure.entity.TestResult dest = new io.qameta.allure.entity.TestResult();
        dest.setUid(uidGenerator.get());
        dest.setHistoryId(result.getHistoryId());
        dest.setFullName(result.getFullName());
        dest.setName(firstNonNull(result.getName(), result.getFullName(), "Unknown test"));
        dest.setTime(Time.create(result.getStart(), result.getStop()));
        dest.setDescription(result.getDescription());
        dest.setDescriptionHtml(sanitizeDescriptionHtml(result.getDescriptionHtml()));
        dest.setStatus(convert(result.getStatus()));
        Optional.ofNullable(result.getStatusDetails()).ifPresent(details -> {
            dest.setStatusMessage(details.getMessage());
            dest.setStatusTrace(details.getTrace());
            dest.setFlaky(details.isFlaky());
        });

        dest.setLinks(convertList(result.getLinks(), this::convert));
        dest.setLabels(convertList(result.getLabels(), this::convert));
        dest.setParameters(getParameters(result));

        dest.addLabelIfNotExists(RESULT_FORMAT, ALLURE2_RESULTS_FORMAT);

        if (hasTestStage(result)) {
            dest.setTestStage(getTestStage(resultsDirectory, visitor, result));
        }

        if (nonNull(result.getUuid())) {
            final List<StageResult> resultBefores = befores.get(result.getUuid());
            if (nonNull(resultBefores)) {
                dest.getBeforeStages().addAll(resultBefores);
            }

            final List<StageResult> resultAfters = afters.get(result.getUuid());
            if (nonNull(resultAfters)) {
                dest.getAfterStages().addAll(resultAfters);
            }
        }
        visitor.visitTestResult(dest);
    }

    private StageResult convert(final Path source,
                                final ResultsVisitor visitor,
                                final FixtureResult result) {
        final StageResult stageResult = new StageResult()
                .setName(result.getName())
                .setTime(convert(result.getStart(), result.getStop()))
                .setStatus(convert(result.getStatus()))
                .setSteps(convertList(result.getSteps(), step -> convert(source, visitor, step)))
                .setDescription(result.getDescription())
                .setDescriptionHtml(sanitizeDescriptionHtml(result.getDescriptionHtml()))
                .setAttachments(convertList(result.getAttachments(), attach -> convert(source, visitor, attach)))
                .setParameters(convertList(result.getParameters(), p -> !HIDDEN.equals(p.getMode()), this::convert));
        Optional.of(result)
                .map(FixtureResult::getStatusDetails)
                .ifPresent(statusDetails -> {
                    stageResult.setStatusMessage(statusDetails.getMessage());
                    stageResult.setStatusTrace(statusDetails.getTrace());
                });

        return stageResult;
    }

    private Link convert(final io.qameta.allure.model.Link link) {
        return new Link()
                .setName(link.getName())
                .setType(link.getType())
                .setUrl(link.getUrl());
    }

    private Label convert(final io.qameta.allure.model.Label label) {
        return new Label()
                .setName(label.getName())
                .setValue(label.getValue());
    }

    private Parameter convert(final io.qameta.allure.model.Parameter parameter) {
        final boolean masked = MASKED.equals(parameter.getMode());
        return new Parameter()
                .setName(parameter.getName())
                .setValue(masked ? "******" : parameter.getValue());
    }

    private Attachment convert(final Path source,
                               final ResultsVisitor visitor,
                               final io.qameta.allure.model.Attachment attachment) {
        final String attachmentSource = attachment.getSource();
        if (!isValidAttachmentFileName(attachmentSource)) {
            visitor.error("Invalid attachment source is provided: " + attachmentSource);
            return new Attachment()
                    .setType(attachment.getType())
                    .setName(attachment.getName())
                    .setSize(0L);
        }
        final Path normalizedSource = source.normalize();
        final Path attachmentFile = normalizedSource.resolve(attachmentSource).normalize();

        if (attachmentFile.startsWith(normalizedSource)
            && Files.isRegularFile(attachmentFile, LinkOption.NOFOLLOW_LINKS)) {
            final Attachment found = visitor.visitAttachmentFile(attachmentFile);
            if (nonNull(attachment.getType())) {
                found.setType(attachment.getType());
                final String ext = getExtensionByMimeType(attachment.getType());
                if (!ext.isEmpty()) {
                    found.setSource(found.getUid() + "." + ext);
                }
            }
            if (nonNull(attachment.getName())) {
                found.setName(attachment.getName());
            }
            return found;
        } else {
            visitor.error("Could not find attachment " + attachmentSource + " in directory " + normalizedSource);
            return new Attachment()
                    .setType(attachment.getType())
                    .setName(attachment.getName())
                    .setSize(0L);
        }
    }

    private Step convert(final Path source,
                         final ResultsVisitor visitor,
                         final StepResult step) {
        final Step result = new Step()
                .setName(step.getName())
                .setStatus(convert(step.getStatus()))
                .setTime(convert(step.getStart(), step.getStop()))
                .setParameters(convertList(step.getParameters(), p -> !HIDDEN.equals(p.getMode()), this::convert))
                .setAttachments(convertList(step.getAttachments(), attachment -> convert(source, visitor, attachment)))
                .setSteps(convertList(step.getSteps(), s -> convert(source, visitor, s)));
        Optional.of(step)
                .map(StepResult::getStatusDetails)
                .ifPresent(statusDetails -> {
                    result.setStatusMessage(statusDetails.getMessage());
                    result.setStatusTrace(statusDetails.getTrace());
                });
        return result;
    }

    private Status convert(final io.qameta.allure.model.Status status) {
        if (Objects.isNull(status)) {
            return Status.UNKNOWN;
        }
        return Stream.of(Status.values())
                .filter(item -> item.value().equalsIgnoreCase(status.value()))
                .findAny()
                .orElse(Status.UNKNOWN);
    }

    private Time convert(final Long start, final Long stop) {
        return new Time()
                .setStart(start)
                .setStop(stop)
                .setDuration(nonNull(start) && nonNull(stop) ? stop - start : null);
    }

    private List<Parameter> getParameters(final TestResult result) {
        final List<Parameter> parameters = convertList(
                result.getParameters(),
                p -> !HIDDEN.equals(p.getMode()),
                this::convert
        );
        if (Objects.isNull(parameters)) {
            return new ArrayList<>();
        }
        final Set<Parameter> parametersSet = new TreeSet<>(PARAMETER_COMPARATOR);
        parametersSet.addAll(parameters);
        return new ArrayList<>(parametersSet);
    }

    private StageResult getTestStage(final Path source,
                                     final ResultsVisitor visitor,
                                     final TestResult result) {
        final StageResult testStage = new StageResult();
        testStage.setSteps(convertList(
                result.getSteps(),
                step -> convert(source, visitor, step)
        ));
        testStage.setAttachments(convertList(
                result.getAttachments(),
                attachment -> convert(source, visitor, attachment)
        ));
        testStage.setStatus(convert(result.getStatus()));
        testStage.setDescription(result.getDescription());
        testStage.setDescriptionHtml(sanitizeDescriptionHtml(result.getDescriptionHtml()));
        Optional.of(result)
                .map(TestResult::getStatusDetails)
                .ifPresent(statusDetails -> {
                    testStage.setStatusMessage(statusDetails.getMessage());
                    testStage.setStatusTrace(statusDetails.getTrace());
                });
        return testStage;
    }

    private boolean hasTestStage(final TestResult result) {
        return !result.getSteps().isEmpty() || !result.getAttachments().isEmpty();
    }

    private String sanitizeDescriptionHtml(final String source) {
        return HtmlSanitizerUtils.sanitizeHtml(source);
    }

    @SafeVarargs
    private static <T> T firstNonNull(final T... items) {
        return Stream.of(items)
                .filter(Objects::nonNull)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "firstNonNull method should have at least one non null parameter"
                ));
    }

    private Stream<TestResultContainer> readTestResultsContainers(final Path resultsDirectory,
                                                                   final boolean sequential) {
        Stream<Path> files = listFiles(resultsDirectory, "*-container.json");
        if (!sequential) {
            files = files.parallel();
        }
        return files
                .map(this::readTestResultContainer)
                .filter(Optional::isPresent)
                .map(Optional::get);
    }

    private Stream<TestResult> readTestResults(final Path resultsDirectory,
                                               final boolean sequential) {
        Stream<Path> files = listFiles(resultsDirectory, "*-result.json");
        if (!sequential) {
            files = files.parallel();
        }
        return files
                .map(this::readTestResult)
                .filter(Optional::isPresent)
                .map(Optional::get);
    }

    private Optional<TestResult> readTestResult(final Path file) {
        try (InputStream is = Files.newInputStream(file)) {
            return Optional.ofNullable(mapper.readValue(is, TestResult.class));
        } catch (IOException e) {
            LOGGER.error("Could not read test result file {}", file, e);
            return Optional.empty();
        }
    }

    private Optional<TestResultContainer> readTestResultContainer(final Path file) {
        try (InputStream is = Files.newInputStream(file)) {
            return Optional.ofNullable(mapper.readValue(is, TestResultContainer.class));
        } catch (IOException e) {
            LOGGER.error("Could not read result container file {}", file, e);
            return Optional.empty();
        }
    }

    private static long sizeSafe(final Path file) {
        try {
            return Files.size(file);
        } catch (IOException ignored) {
            return 0L;
        }
    }

    private Stream<Path> listFiles(final Path directory, final String glob) {
        try (DirectoryStream<Path> directoryStream = newDirectoryStream(directory, glob)) {
            return StreamSupport.stream(directoryStream.spliterator(), true)
                    .filter(Files::isRegularFile)
                    .collect(Collectors.toList())
                    .stream();
        } catch (IOException e) {
            LOGGER.error("Could not list files in directory {}", directory, e);
            return Stream.empty();
        }
    }

    private static boolean isValidAttachmentFileName(final String fileName) {
        return nonNull(fileName) && ATTACHMENT_SOURCE_PATTERN.matcher(fileName).matches();

    }

}
