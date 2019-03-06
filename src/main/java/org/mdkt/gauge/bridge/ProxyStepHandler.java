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

import com.google.protobuf.ByteString;
import com.thoughtworks.gauge.Gauge;
import com.thoughtworks.gauge.Step;
import com.thoughtworks.gauge.StepValue;
import gauge.messages.Messages;
import gauge.messages.Spec;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.Charset;

@Aspect
@Component
public class ProxyStepHandler {
    private static final Logger logger = LoggerFactory.getLogger(ProxyStepHandler.class);

    @Autowired
    GaugeBridgeRuntime runtime;

    @Around("@annotation(proxyStep) && @annotation(step)")
    public Object handle(ProceedingJoinPoint joinPoint, ProxyStep proxyStep, Step step) throws Throwable {
        LanguageRunner lr = proxyStep.value();
        for (String stepText : step.value()) {
            StepValue sv = runtime.getStepValue(stepText);
            String actualStepText = String.format(sv.getStepText().replaceAll("\\{\\}", "\"%s\""), joinPoint.getArgs());
            logger.debug("Handling\nactualStepText: {}\nparsedStepText: {}\nparameters: {}", actualStepText, sv.getStepText(), joinPoint.getArgs());
            Messages.ExecuteStepRequest.Builder requestBuilder = Messages.ExecuteStepRequest.newBuilder()
                    .setActualStepText(actualStepText)
                    .setParsedStepText(sv.getStepText());
            for (Object paramValue : joinPoint.getArgs()) {
                requestBuilder.addParameters(Spec.Parameter.newBuilder()
                        .setValue(String.valueOf(paramValue))
                        .setParameterType(Spec.Parameter.ParameterType.Static)
                        .build());
            }
            Messages.ExecuteStepRequest executeStepRequest = requestBuilder.build();

            preStep(lr, executeStepRequest);

            Messages.Message msg = runtime.newMessageBuilder()
                    .setMessageType(Messages.Message.MessageType.ExecuteStep)
                    .setExecuteStepRequest(executeStepRequest)
                    .build();
            Spec.ProtoExecutionResult result = runtime.executeAndGetStatus(lr, msg);
            for (ByteString bs : result.getMessageList().asByteStringList()) {
                Gauge.writeMessage(bs.toString(Charset.defaultCharset()));
            }
            if (result.getFailed()) {
                throw new RuntimeException(result.getErrorMessage() + " \n" + result.getStackTrace());
            }
            postStep(lr);
        }
        return joinPoint.proceed();
    }

    private void postStep(LanguageRunner lr) {
        Messages.Message postMsg = runtime.newMessageBuilder()
                .setMessageType(Messages.Message.MessageType.StepExecutionEnding)
                .setExecutionEndingRequest(Messages.ExecutionEndingRequest.newBuilder()
                        .build())
                .build();
        Spec.ProtoExecutionResult postResult = runtime.executeAndGetStatus(lr, postMsg);
        if (postResult.getFailed()) {
            throw new RuntimeException("Posthook failed: " + postResult.getErrorMessage());
        }
    }

    private void preStep(LanguageRunner lr, Messages.ExecuteStepRequest executeStepRequest) {
        Messages.Message preMsg = runtime.newMessageBuilder()
                .setMessageType(Messages.Message.MessageType.StepExecutionStarting)
                .setStepExecutionStartingRequest(Messages.StepExecutionStartingRequest.newBuilder()
                        .setCurrentExecutionInfo(Messages.ExecutionInfo.newBuilder()
                                .setCurrentStep(Messages.StepInfo.newBuilder()
                                        .setIsFailed(false)
                                        .setStep(executeStepRequest)
                                        .build())
                                .build())
                        .build())
                .build();
        Spec.ProtoExecutionResult preResult = runtime.executeAndGetStatus(lr, preMsg);
        if (preResult.getFailed()) {
            throw new RuntimeException("Prehook failed: " + preResult.getErrorMessage());
        }
    }
}
