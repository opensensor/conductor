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
package com.netflix.conductor.redislock;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

import org.redisson.Redisson;

import com.netflix.conductor.core.config.ConductorProperties;
import com.netflix.conductor.core.sync.Lock;
import com.netflix.conductor.redislock.config.RedisLockConfiguration;
import com.netflix.conductor.redislock.config.RedisLockProperties;
import com.netflix.conductor.service.ExecutionLockService;

/** CI-compiled isolated probe; all application classes come from the pinned runtime JAR. */
public final class NativeRedisLockProbe {
    private NativeRedisLockProbe() {}

    public static void main(String[] args) throws Exception {
        String address = System.getenv("CONDUCTOR_PROBE_REDIS");
        if (address == null || !address.matches("redis://10\\.0\\.2\\.2:[0-9]{1,5}")) {
            throw new IllegalArgumentException(
                    "Only isolated loopback fixture forwarding is allowed");
        }
        RedisLockProperties redisProperties = new RedisLockProperties();
        redisProperties.setServerAddress(address);
        redisProperties.setServerPassword(System.getenv("CONDUCTOR_PROBE_PASSWORD"));
        redisProperties.setNamespace("conductor-lock-proof");
        redisProperties.setNumNettyThreads(2);
        if (redisProperties.isIgnoreLockingExceptions()) {
            throw new IllegalStateException("Lock errors must remain fail-closed");
        }
        RedisLockConfiguration factory = new RedisLockConfiguration();
        Redisson redisson = factory.getRedisson(redisProperties);
        try {
            Lock lock = factory.provideLock(redisson, redisProperties);
            ConductorProperties properties = new ConductorProperties();
            properties.setWorkflowExecutionLockEnabled(true);
            ExecutionLockService execution = new ExecutionLockService(properties, lock);
            if (properties.getLockLeaseTime().toMillis() != 60000
                    || redisson.getConfig().getLockWatchdogTimeout() != 30000) {
                throw new IllegalStateException("Pinned lease/watchdog profile changed");
            }
            if (!execution.acquireLock("explicit")) {
                throw new IllegalStateException("Native explicit lease was not acquired");
            }
            execution.waitForLock("watchdog");
            if (!lock.acquireLock("timed-watchdog", 500, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("Native timed watchdog lease was not acquired");
            }
            Thread contender =
                    new Thread(() -> lock.acquireLock("watchdog", 60000, TimeUnit.MILLISECONDS));
            contender.setDaemon(true);
            contender.start();
            System.out.println(
                    "NATIVE_LOCK_PROBE_READY {\"explicit_lease_ms\":60000,\"watchdog_ms\":30000,\"lock_exception_ignore\":false}");
            System.out.flush();
            // Keep the owning JVM alive after revocation: shutdown is never the fencing proof.
            // The harness observes live TTLs and durable gate QUIT receipts before sending STOP.
            try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in))) {
                while (true) {
                    String line = input.readLine();
                    if (line == null || line.equals("STOP")) {
                        break;
                    }
                }
            }
        } finally {
            redisson.shutdown(0, 2, TimeUnit.SECONDS);
        }
    }
}
