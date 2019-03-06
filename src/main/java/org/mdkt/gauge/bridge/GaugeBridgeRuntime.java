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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.thoughtworks.gauge.GaugeConstant;
import com.thoughtworks.gauge.StepValue;
import com.thoughtworks.gauge.connection.GaugeConnection;
import com.thoughtworks.gauge.scan.ClasspathScanner;
import gauge.messages.Messages;
import gauge.messages.Spec;
import org.apache.commons.lang.SystemUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 *
 */
@Service
public class GaugeBridgeRuntime {
    private static final Logger logger = LoggerFactory.getLogger(GaugeBridgeRuntime.class);
    private GaugeConnection connection;
    private Map<String, StepValue> stepsRegistry;
    private Map<LanguageRunner, Socket> languageRunnerClientRegistry;
    private Set<LanguageRunner> languageRunnerFinish;
    private BlockingQueue<Messages.Message> responseQueue;
    private AtomicInteger messageId;
    private CountDownLatch serverStarted;

    public GaugeBridgeRuntime() {
        this.connection = new GaugeConnection(readEnvVar(GaugeConstant.GAUGE_API_PORT));
        this.stepsRegistry = new HashMap<>();
        this.languageRunnerClientRegistry = new HashMap<>();
        this.responseQueue = new ArrayBlockingQueue<>(5);
        this.messageId = new AtomicInteger(1);
        this.languageRunnerFinish = new HashSet<>();
    }

    public StepValue getStepValue(String stepText) {
        return stepsRegistry.get(stepText);
    }

    @PostConstruct
    public void start() {
        long startTime = System.currentTimeMillis();
        ClasspathScanner classpathScanner = new ClasspathScanner();
        ProxyStepsScanner stepsScanner = new ProxyStepsScanner();
        classpathScanner.scan(stepsScanner);
        for (LanguageRunner lr : stepsScanner.getLanguageRunners()) {
            logger.info("[{}] Validating proxy steps", lr);
            List<String> stepNames = stepsScanner.getStepNames(lr);
            if (stepNames.size() == 0) {
                continue;
            }
            if (languageRunnerClientRegistry.get(lr) == null) {
                serverStarted = new CountDownLatch(1);
                try {
                    int port = startServer(lr);
                    new Thread(() -> startRunner(lr, port),
                            String.format("language-runner-%s", lr)).start();
                    serverStarted.await();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
            if (!validateSteps(lr, stepNames)) {
                throw new RuntimeException("[" + lr + "] step validation fails");
            }
            logger.debug("[{}] notifyBeforeSuite:: ExecutionStarting", lr);
            Messages.Message beforeSuiteMsg = newMessageBuilder()
                    .setMessageType(Messages.Message.MessageType.ExecutionStarting)
                    .setExecutionStartingRequest(Messages.ExecutionStartingRequest.newBuilder()
                            .build())
                    .build();
            Spec.ProtoExecutionResult beforeSuiteResult = executeAndGetStatus(lr, beforeSuiteMsg);
            if (beforeSuiteResult.getFailed()) {
                throw new RuntimeException("[" + lr + "] BeforeSuite fails");
            }
        }
        logger.info("Started BridgeRuntime in {} seconds", (System.currentTimeMillis() - startTime) / 1000);
    }

    @PreDestroy
    public void finish() {
        logger.info("Stopping all runners");
        for (LanguageRunner lr : languageRunnerClientRegistry.keySet()) {
            Messages.Message killMsg = newMessageBuilder()
                    .setMessageType(Messages.Message.MessageType.KillProcessRequest)
                    .setKillProcessRequest(Messages.KillProcessRequest.newBuilder()
                            .build())
                    .build();
            languageRunnerFinish.add(lr);
            Spec.ProtoExecutionResult killResult = executeAndGetStatus(lr, killMsg);
            if (killResult.getFailed()) {
                logger.error("Kill {} runner failed due to {}", lr, killResult.getErrorMessage());
            }
        }
    }

    /**
     * Create a new builder with prepopulated messageId
     *
     * @return
     */
    public Messages.Message.Builder newMessageBuilder() {
        return Messages.Message.newBuilder().setMessageId(messageId.getAndIncrement());
    }

    public Spec.ProtoExecutionResult executeAndGetStatus(LanguageRunner lr, Messages.Message msg) {
        Socket socket = languageRunnerClientRegistry.get(lr);
        try {
            logger.debug("Request --- \n{}\n---------", msg);
            socket.getOutputStream().write(toData(msg.toByteArray()));
            socket.getOutputStream().flush();
            Messages.Message response = responseQueue.take();
            ensureMessageType(response.getMessageType()).is(Messages.Message.MessageType.ExecutionStatusResponse);
            logger.debug("Response --- \n{}\n---------", response);
            return response.getExecutionStatusResponse().getExecutionResult();
        } catch (Exception e) {
            throw new RuntimeException("execute error", e);
        }
    }

    private static MessageTypeChecker ensureMessageType(Messages.Message.MessageType type) {
        return new MessageTypeChecker(type);
    }

    private boolean validateSteps(LanguageRunner lr, List<String> stepNames) {
        Socket socket = languageRunnerClientRegistry.get(lr);
        try {
            for (String step : stepNames) {
                logger.debug("[{}] Step: {}", lr, step);
                StepValue sv = connection.getStepValue(step);
                stepsRegistry.put(step, sv);
                Spec.ProtoStepValue protoStepValue = Spec.ProtoStepValue.newBuilder()
                        .addAllParameters(sv.getParameters())
                        .setParameterizedStepValue(sv.getStepAnnotationText())
                        .setStepValue(sv.getStepText())
                        .build();
                Messages.Message msg = newMessageBuilder()
                        .setMessageType(Messages.Message.MessageType.StepValidateRequest)
                        .setStepValidateRequest(Messages.StepValidateRequest.newBuilder()
                                .setStepText(protoStepValue.getStepValue())
                                .setStepValue(protoStepValue)
                                .build())
                        .build();
                socket.getOutputStream().write(toData(msg.toByteArray()));
                socket.getOutputStream().flush();

                Messages.Message response = responseQueue.take();
                ensureMessageType(response.getMessageType()).is(Messages.Message.MessageType.StepValidateResponse);
                if (!response.getStepValidateResponse().getIsValid()) {
                    throw new RuntimeException(response.getStepValidateResponse().getErrorMessage() + "\n" + response.getStepValidateResponse().getSuggestion());
                }
            }
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("validation error", e);
        }

        return true;
    }

    private int readEnvVar(String env) {
        String port = System.getenv(env);
        if (port == null || port.equalsIgnoreCase("")) {
            throw new RuntimeException(env + " not set");
        }
        return Integer.parseInt(port);
    }

    static class RunnerInfo {
        public String id;
        public String name;
        public String version;
        public String description;
        public Map<String, List<String>> run;
        public Map<String, List<String>> init;
        public Map<String, String> gaugeVersionSupport;
        public String lspLangId;
    }

    private static RunnerInfo getRunnerInfo(LanguageRunner language) {
        String pluginJsonPath = Common.getLanguageJSONFilePath(language.name());
        try {
            return new ObjectMapper().readValue(new File(pluginJsonPath), RunnerInfo.class);
        } catch (IOException e) {
            throw new RuntimeException("Unable to read descriptor for language " + language, e);
        }
    }

    private void startRunner(LanguageRunner language, int internalPort) {
        RunnerInfo info = getRunnerInfo(language);
        List<String> cmd = null;
        if (SystemUtils.IS_OS_WINDOWS) {
            cmd = info.run.get("windows");
        } else if (SystemUtils.IS_OS_MAC || SystemUtils.IS_OS_MAC_OSX) {
            cmd = info.run.get("darwin");
        } else if (SystemUtils.IS_OS_LINUX) {
            cmd = info.run.get("linux");
        }
        if (cmd == null) {
            throw new RuntimeException("No command found for the OS");
        }
        try {
            ProcessBuilder processBuilder = new ProcessBuilder()
                    .command(cmd)
                    .directory(new File(Common.getLanguageJSONFilePath(language.name())).getParentFile())
                    .inheritIO();
            processBuilder.environment().put("GAUGE_INTERNAL_PORT", String.valueOf(internalPort));
            Process runner = processBuilder
                    .start();
            if (runner.waitFor() != 0) {
                throw new RuntimeException("command run failed");
            }
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Unable to execute command " + cmd, e);
        }
    }

    private int startServer(LanguageRunner lr) {
        // need to start a socket server to accept the initial request from the runner
        try {
            ServerSocket server = new ServerSocket(0);
            logger.debug("Internal Server for language {} started on {}", lr, server.getLocalPort());
            new Thread(() -> {
                Socket socket = null;
                try {
                    socket = server.accept();
                    languageRunnerClientRegistry.put(lr, socket);
                    serverStarted.countDown();
                    InputStream inputStream = socket.getInputStream();
                    while (socket.isConnected()) {
                        MessageLength messageLength = getMessageLength(inputStream);
                        Messages.Message response = Messages.Message.parseFrom(toBytes(messageLength));
                        responseQueue.put(response);
                    }
                } catch (Exception e) {
                    if (!languageRunnerFinish.contains(lr)) {
                        throw new RuntimeException("reading socket error", e);
                    }
                } finally {
                    try {
                        if (socket != null) {
                            socket.close();
                        }
                    } catch (IOException e) {
                        logger.warn("closing socket error: {}", e.getMessage());
                    }
                }
            }, String.format("server-%s", lr)).start();
            return server.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException("starting socket server error", e);
        }
    }

    private static byte[] toData(byte[] bytes) throws IOException {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        CodedOutputStream cos = CodedOutputStream.newInstance(stream);
        cos.writeUInt64NoTag(bytes.length);
        cos.flush();
        stream.write(bytes);
        stream.close();

        return stream.toByteArray();
    }

    private static byte[] toBytes(MessageLength messageLength) throws IOException {
        long messageSize = messageLength.getLength();
        CodedInputStream stream = messageLength.getRemainingStream();
        return stream.readRawBytes((int) messageSize);
    }

    static class MessageLength {
        private long length;
        private CodedInputStream remainingStream;

        MessageLength(long length, CodedInputStream remainingStream) {
            this.length = length;
            this.remainingStream = remainingStream;
        }

        public long getLength() {
            return length;
        }

        public CodedInputStream getRemainingStream() {
            return remainingStream;
        }
    }

    private static MessageLength getMessageLength(InputStream is) throws IOException {
        CodedInputStream codedInputStream = CodedInputStream.newInstance(is);
        long size = codedInputStream.readRawVarint64();
        return new MessageLength(size, codedInputStream);
    }

    private static class MessageTypeChecker {
        private Messages.Message.MessageType actualType;

        public MessageTypeChecker(Messages.Message.MessageType actualType) {
            this.actualType = actualType;
        }

        public void is(Messages.Message.MessageType expectedType) {
            if (actualType != expectedType) {
                throw new RuntimeException("unexpected message type. Expected " + expectedType + " but got " + actualType);
            }
        }
    }
}
