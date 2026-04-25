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
import io.qameta.allure.core.LaunchResults;

import java.util.List;

/**
 * Aggregator extension. Can be used to process results and/or generate
 * some data to report directory.
 *
 * @since 2.0
 */
@FunctionalInterface
public interface Aggregator2 extends Extension {

    /**
     * Process report data.
     *
     * <p><b>Streaming-mode contract:</b> when the report is generated in streaming
     * mode ({@code allure.streaming.generate=true}), each {@link io.qameta.allure.entity.TestResult}
     * inside {@code launchesResults} has its stage trees stripped
     * ({@code testStage} is an empty {@link io.qameta.allure.entity.StageResult},
     * {@code beforeStages} and {@code afterStages} are empty lists).
     * The full step/attachment data has already been written to
     * {@code data/test-cases/*.json} during the read phase.
     * Aggregators that only consume metadata (status, labels, parameters, etc.)
     * are unaffected; aggregators that read step trees must not be used in
     * streaming mode.
     *
     * @param configuration   the report configuration.
     * @param launchesResults all the parsed test results.
     * @param storage         the report storage.
     */
    void aggregate(Configuration configuration,
                   List<LaunchResults> launchesResults,
                   ReportStorage storage);

    /**
     * Returns {@code true} when this aggregator writes per-test-case files
     * (e.g. {@code data/test-cases/*.json}) to storage.
     *
     * <p>The streaming generate path skips such aggregators because
     * test-case files are already written during the read phase.
     * Override to return {@code true} in any aggregator that produces
     * {@code data/test-cases/*} entries.
     *
     * <p>Defaults to {@code false}.
     */
    default boolean isTestCaseFileWriter() {
        return false;
    }

}
