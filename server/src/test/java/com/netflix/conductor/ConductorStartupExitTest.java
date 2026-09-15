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
package com.netflix.conductor;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ConductorStartupExitTest {

    @Test
    void startupFailureExitsEvenWhenAClientLeavesANonDaemonThread() throws Exception {
        verifyStartupExit(null);
    }

    @Test
    void originalMainDemonstratesTheStuckProcess() throws Exception {
        String baseline = System.getProperty("conductor.test.startupBaseline");
        assumeTrue(baseline != null, "The native baseline is supplied by the manual CI workflow");
        assertTrue(Files.isRegularFile(Path.of(baseline, "com/netflix/conductor/Conductor.class")));
        verifyStartupExit(baseline);
    }

    private void verifyStartupExit(String baseline) throws Exception {
        Path output = Files.createTempFile("conductor-startup-exit-", ".log");
        Process process = null;
        try {
            process =
                    new ProcessBuilder(
                                    Path.of(System.getProperty("java.home"), "bin", "java")
                                            .toString(),
                                    "-Xmx256m",
                                    "-cp",
                                    (baseline == null ? "" : baseline + File.pathSeparator)
                                            + System.getProperty("conductor.test.classpath"),
                                    StartupFailureFixture.class.getName())
                            .redirectErrorStream(true)
                            .redirectOutput(output.toFile())
                            .start();
            if (baseline == null) {
                assertTrue(
                        process.waitFor(30, TimeUnit.SECONDS), "Failed startup left the JVM alive");
                assertEquals(1, process.exitValue());
            } else {
                assertFalse(
                        process.waitFor(10, TimeUnit.SECONDS), "Original bug did not reproduce");
            }
            String logs = Files.readString(output);
            assertTrue(logs.contains("NON_DAEMON_CLIENT_STARTED"));
            assertTrue(logs.contains("invalid-fixture-value"), logs);
            if (baseline == null) {
                assertTrue(logs.contains("Conductor startup failed"), logs);
            }
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            }
            Files.deleteIfExists(output);
        }
    }

    public static class StartupFailureFixture {
        public static void main(String[] args) throws Exception {
            Thread client =
                    new Thread(
                            () -> {
                                while (true) {
                                    LockSupport.park();
                                }
                            },
                            "leftover-client-thread");
            client.setDaemon(false);
            client.start();
            System.out.println("NON_DAEMON_CLIENT_STARTED");
            // Real Spring configuration binding fails before dependencies start.
            Conductor.main(new String[] {"--spring.main.banner-mode=invalid-fixture-value"});
        }
    }
}
