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

import com.thoughtworks.gauge.Step;
import com.thoughtworks.gauge.scan.IScanner;
import org.reflections.Reflections;

import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;

/**
 * This is to re-scan steps annotated with an extended language support
 */
public class ProxyStepsScanner implements IScanner {
    private Map<LanguageRunner, List<String>> stepNames;

    public ProxyStepsScanner() {
        this.stepNames = new HashMap<>();
    }

    @Override
    public void scan(Reflections reflections) {
        Set<Method> methods = reflections.getMethodsAnnotatedWith(ProxyStep.class);
        for (Method m : methods) {
            Step a = m.getAnnotation(Step.class);
            ProxyStep ps = m.getAnnotation(ProxyStep.class);
            if (a != null) {
                List<String> steps = stepNames.get(ps.value());
                if (steps == null) {
                    steps = new ArrayList<>();
                    stepNames.put(ps.value(), steps);
                }
                steps.addAll(Arrays.stream(a.value()).collect(Collectors.toList()));
            }
        }
    }

    public List<String> getStepNames(LanguageRunner lr) {
        if (stepNames.containsKey(lr)) {
            return stepNames.get(lr);
        } else {
            return new ArrayList<>();
        }
    }

    public Set<LanguageRunner> getLanguageRunners() {
        return stepNames.keySet();
    }
}
