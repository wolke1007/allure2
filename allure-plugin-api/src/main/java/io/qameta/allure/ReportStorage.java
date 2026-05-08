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

import java.nio.file.Path;

/**
 * @author charlie (Dmitry Baev).
 */
public interface ReportStorage {

    void addDataJson(String name, Object data);

    void addDataBinary(String name, byte[] data);

    void addDataFile(String name, Path file);

    /**
     * Reads previously written data back as raw bytes. Used by streaming generate mode
     * to merge late-bound aggregator output (e.g. tags/severity/categories) into already
     * persisted test-case JSON without keeping the full result tree in memory.
     *
     * <p>Default implementation throws {@link UnsupportedOperationException} so existing
     * third-party storage implementations remain source-compatible. Implementations that
     * support read-back must override this method.
     *
     * @param name the resource name previously passed to {@code addDataJson} or
     *             {@code addDataBinary}
     * @return the raw bytes that were written
     * @throws UnsupportedOperationException if this storage does not support reading
     */
    default byte[] readDataBinary(String name) {
        throw new UnsupportedOperationException(
                "This storage does not support reading data back: " + getClass().getName());
    }

}
