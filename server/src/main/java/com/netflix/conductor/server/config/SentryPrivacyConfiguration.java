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

import java.util.Collections;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.sentry.KeyValueCollectionBehavior;
import io.sentry.Sentry;
import io.sentry.SentryBaseEvent;
import io.sentry.SentryOptions;

/** Keep workflow inputs, credentials and customer records out of error telemetry. */
@Configuration(proxyBeanMethods = false)
public class SentryPrivacyConfiguration {
    @Bean
    public Sentry.OptionsConfiguration<SentryOptions> conductorSentryPrivacy() {
        return options -> {
            var collection = options.getDataCollection();
            collection.setUserInfo(false);
            collection.setCookies(KeyValueCollectionBehavior.off());
            collection.setUrlQueryParams(KeyValueCollectionBehavior.off());
            collection.setHttpBodies(Collections.emptySet());
            collection.setDatabaseQueryData(false);
            collection.getHttpHeaders().setRequest(KeyValueCollectionBehavior.off());
            collection.getHttpHeaders().setResponse(KeyValueCollectionBehavior.off());
            collection.getGraphql().setDocument(false);
            collection.getGraphql().setVariables(false);
            options.getLogs().setEnabled(false);
            options.setBeforeBreadcrumb((breadcrumb, hint) -> null);
            options.setBeforeSend(
                    (event, hint) -> {
                        // Report exceptions, never message-only logs containing workflow data.
                        if (event.getExceptions() == null || event.getExceptions().isEmpty()) {
                            return null;
                        }
                        scrub(event);
                        event.setMessage(null);
                        if (event.getExceptions() != null) {
                            // Exception messages can interpolate entire workflow payloads or DSNs.
                            // Preserve exception types and source frames for grouping and
                            // diagnosis.
                            event.getExceptions()
                                    .forEach(exception -> exception.setValue("[Filtered]"));
                        }
                        return event;
                    });
            options.setBeforeSendTransaction(
                    (transaction, hint) -> {
                        scrub(transaction);
                        return transaction;
                    });
        };
    }

    private static void scrub(SentryBaseEvent event) {
        event.setRequest(null);
        event.setUser(null);
        event.setExtras(null);
        event.setBreadcrumbs(null);
    }
}
