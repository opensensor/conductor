/*
 * Copyright 2026 Conductor Authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package com.netflix.conductor.server.config;

import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;
import io.sentry.Sentry;
import io.sentry.protocol.User;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SentryPrivacyConfigurationTest {
    @Test
    void realEnvelopeKeepsSourceFramesWithoutWorkflowData() throws Exception {
        var envelopes = new LinkedBlockingQueue<String>();
        var receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext(
                "/api/1/envelope/",
                exchange -> {
                    byte[] bytes = exchange.getRequestBody().readAllBytes();
                    if ("gzip".equals(exchange.getRequestHeaders().getFirst("Content-Encoding"))) {
                        bytes = new GZIPInputStream(new ByteArrayInputStream(bytes)).readAllBytes();
                    }
                    envelopes.add(new String(bytes, StandardCharsets.UTF_8));
                    exchange.sendResponseHeaders(200, -1);
                    exchange.close();
                });
        receiver.start();
        try {
            Sentry.init(
                    options -> {
                        options.setDsn(
                                "http://public@127.0.0.1:"
                                        + receiver.getAddress().getPort()
                                        + "/1");
                        options.setEnvironment("test");
                        options.setEnableAutoSessionTracking(false);
                        new SentryPrivacyConfiguration()
                                .conductorSentryPrivacy()
                                .configure(options);
                    });
            var user = new User();
            user.setEmail("private-workflow-user@example.invalid");
            Sentry.setUser(user);
            Sentry.setExtra("workflow", "private-workflow-body");
            Sentry.addBreadcrumb("private-workflow-breadcrumb");
            try {
                throw new IllegalStateException("private-workflow-password");
            } catch (IllegalStateException failure) {
                Sentry.captureException(failure);
            }
            Sentry.flush(5000);
            String envelope = envelopes.poll(5, TimeUnit.SECONDS);
            assertNotNull(envelope);
            assertTrue(envelope.contains("IllegalStateException"));
            assertTrue(envelope.contains("SentryPrivacyConfigurationTest.java"));
            assertFalse(envelope.contains("private-workflow"));
        } finally {
            Sentry.close();
            receiver.stop(0);
        }
    }
}
