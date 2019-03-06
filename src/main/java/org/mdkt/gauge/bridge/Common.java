/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.mdkt.gauge.bridge;

import org.apache.commons.lang.SystemUtils;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.Optional;

/**
 * This is a partial port of {@code github.com/getgauge/common/common.go}
 */
public class Common {
    private static final String GAUGE_HOME = "GAUGE_HOME";
    private static final String APP_DATA = "APPDATA";
    private static final String PRODUCT_NAME = "gauge";
    private static final String DOT_GAUGE = ".gauge";
    private static final String PLUGINS = "plugins";

    public static String getGaugeHomeDirectory() {
        String customPluginRoot = System.getenv(GAUGE_HOME);
        if (!StringUtils.isEmpty(customPluginRoot)) {
            return customPluginRoot;
        }
        if (SystemUtils.IS_OS_WINDOWS) {
            String appDataDir = System.getenv(APP_DATA);
            if (StringUtils.isEmpty(appDataDir)) {
                throw new RuntimeException("Failed to find plugin installation path. Could not get APPDATA");
            }
            return new File(appDataDir, PRODUCT_NAME).getAbsolutePath();
        }
        return new File(System.getProperty("user.home"), DOT_GAUGE).getAbsolutePath();
    }

    public static String getPrimaryPluginsInstallDir() {
        return new File(getGaugeHomeDirectory(), PLUGINS).getAbsolutePath();
    }

    public static String[] getPluginInstallPrefixes() {
        return new String[]{getPrimaryPluginsInstallDir()};
    }

    public static String getPluginsInstallDir(String pluginName) {
        String[] pluginInstallPrefixes = getPluginInstallPrefixes();
        for (String prefix : pluginInstallPrefixes) {
            if (new File(prefix, pluginName).isDirectory()) {
                return prefix;
            }
        }
        throw new RuntimeException("Plugin " + pluginName + " not installed on the following locations: " + pluginInstallPrefixes);
    }

    public static String getInstallDir(String pluginName, String pluginVersion) {
        String allPluginsInstallDir = getPluginsInstallDir(pluginName);
        File pluginDir = new File(allPluginsInstallDir, pluginName);
        if (StringUtils.isEmpty(pluginVersion)) {
            Path path = Paths.get(pluginDir.getAbsolutePath());
            try {
                Optional<Path> lastFilePath = Files.list(path)
                        .filter(f -> Files.isDirectory(f))
                        .max(Comparator.comparingLong(f -> f.toFile().lastModified()));
                if (lastFilePath.isPresent()) {
                    return lastFilePath.get().toString();
                }
                throw new RuntimeException("No version found for plugin " + pluginName);
            } catch (IOException e) {
                throw new RuntimeException(path + " does not exist", e);
            }
        }
        return new File(pluginDir, pluginVersion).getAbsolutePath();
    }

    public static String getLanguageJSONFilePath(String language) {
        String languageInstallDir = getInstallDir(language, "");
        File languageJson = new File(languageInstallDir, String.format("%s.json", language));
        if (languageJson.exists() && languageJson.isFile()) {
            return languageJson.getAbsolutePath();
        }
        throw new RuntimeException("Failed to find the implementation for: " + language + ". " + languageJson.getAbsolutePath() + " does not exist");
    }
}
