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
package org.conductoross.conductor.es8.dao.index;

import java.util.UUID;

import org.conductoross.conductor.es8.config.ElasticSearchProperties;

import co.elastic.clients.elasticsearch.ElasticsearchClient;

/** Per-logical-write identity, retained by the request client across transport retries. */
public final class JournalRequestIdentity {
    private JournalRequestIdentity() {}

    public static String generation(ElasticSearchProperties properties) {
        Long value = properties.getJournalGeneration();
        if (value == null) {
            return null;
        }
        if (value < 1) {
            throw new IllegalStateException("Invalid journal writer generation");
        }
        return value.toString();
    }

    static ElasticsearchClient forWrite(
            ElasticsearchClient source, ElasticSearchProperties properties) {
        String generation = generation(properties);
        if (generation == null) {
            return source;
        }
        String requestId = UUID.randomUUID().toString();
        return source.withTransportOptions(
                options ->
                        options.setHeader("X-OpenSensor-Generation", generation)
                                .setHeader("X-OpenSensor-Request-ID", requestId));
    }
}
