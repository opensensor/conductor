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

import java.util.List;
import java.util.Map;

import org.apache.http.HttpHost;
import org.conductoross.conductor.es8.config.ElasticSearchProperties;
import org.elasticsearch.client.RestClient;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.utility.DockerImageName;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Actual typed-client wire compatibility; this does not certify the separate journal gateway. */
public class JournalElasticsearchCompatibilityTest {
    private static final ElasticsearchContainer container =
            new ElasticsearchContainer(
                            DockerImageName.parse(
                                    "docker.elastic.co/elasticsearch/elasticsearch@sha256:f456578fc2a620a8a4f4c21d070fff1f6070345adb2be5e5626b65be72aea350"))
                    .withEnv("xpack.security.enabled", "false")
                    .withEnv("xpack.ml.enabled", "false")
                    .withEnv("discovery.type", "single-node")
                    .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
                    .withCreateContainerCmdModifier(
                            command ->
                                    command.getHostConfig()
                                            .withMemory(2L * 1024 * 1024 * 1024)
                                            .withNanoCPUs(1_000_000_000L));
    private static RestClientTransport transport;
    private static ElasticsearchClient client;
    private static final ElasticSearchProperties profile = new ElasticSearchProperties();

    @BeforeClass
    public static void start() throws Exception {
        // A missing Docker runtime fails this compatibility gate instead of skipping it.
        container.start();
        RestClient rest =
                RestClient.builder(HttpHost.create("http://" + container.getHttpHostAddress()))
                        .build();
        transport = new RestClientTransport(rest, new JacksonJsonpMapper());
        client = new ElasticsearchClient(transport);
        assertEquals("9.5.3", client.info().version().number());
        profile.setJournalGeneration(9L);
        profile.setAutoIndexManagementEnabled(false);
        profile.setRefreshOnWrite(true);
        client.indices()
                .create(
                        index ->
                                index.index("conductor_task-000001")
                                        .settings(
                                                settings ->
                                                        settings.numberOfShards("1")
                                                                .numberOfReplicas("0"))
                                        .mappings(
                                                mapping ->
                                                        mapping.properties(
                                                                        "number",
                                                                        property ->
                                                                                property.long_(
                                                                                        field ->
                                                                                                field))
                                                                .properties(
                                                                        "workflowId",
                                                                        property ->
                                                                                property.keyword(
                                                                                        field ->
                                                                                                field)))
                                        .aliases(
                                                "conductor_task",
                                                alias -> alias.isWriteIndex(true)));
    }

    @AfterClass
    public static void stop() throws Exception {
        if (transport != null) {
            transport.close();
        }
        container.stop();
    }

    private ElasticsearchClient write() {
        return JournalRequestIdentity.forWrite(client, profile);
    }

    @Test
    public void typedClientHandlesSupportedWritesAndPartialBulkRejectionsOnPinnedEs()
            throws Exception {
        var first =
                write().index(
                                index ->
                                        index.index("conductor_task")
                                                .id("full")
                                                .document(
                                                        Map.of(
                                                                "number",
                                                                1,
                                                                "workflowId",
                                                                "cleanup",
                                                                "nested",
                                                                Map.of("retained", true)))
                                                .refresh(Refresh.True));
        assertEquals("conductor_task-000001", first.index());
        var generated =
                write().index(
                                index ->
                                        index.index("conductor_task")
                                                .document(Map.of("number", 2))
                                                .refresh(Refresh.True));
        assertNotNull(generated.id());
        var update =
                write().update(
                                request ->
                                        request.index("conductor_task")
                                                .id("full")
                                                .doc(Map.of("number", 3))
                                                .refresh(Refresh.True),
                                Map.class);
        assertEquals("full", update.id());
        Map source =
                client.get(request -> request.index("conductor_task").id("full"), Map.class)
                        .source();
        assertEquals(3, source.get("number"));
        assertEquals(Map.of("retained", true), source.get("nested"));
        BulkResponse bulk =
                write().bulk(
                                request ->
                                        request.refresh(Refresh.True)
                                                .operations(
                                                        op ->
                                                                op.index(
                                                                        index ->
                                                                                index.index(
                                                                                                "conductor_task")
                                                                                        .id(
                                                                                                "bulk-good")
                                                                                        .document(
                                                                                                Map
                                                                                                        .of(
                                                                                                                "number",
                                                                                                                4))))
                                                .operations(
                                                        op ->
                                                                op.index(
                                                                        index ->
                                                                                index.index(
                                                                                                "conductor_task")
                                                                                        .id(
                                                                                                "bulk-bad")
                                                                                        .document(
                                                                                                Map
                                                                                                        .of(
                                                                                                                "number",
                                                                                                                "not-a-number")))));
        assertTrue(bulk.errors());
        assertEquals(201, bulk.items().getFirst().status());
        assertEquals(400, bulk.items().getLast().status());
        assertEquals("conductor_task-000001", bulk.items().getLast().index());
        var deleted =
                write().deleteByQuery(
                                request ->
                                        request.index("conductor_task")
                                                .query(
                                                        query ->
                                                                query.term(
                                                                        term ->
                                                                                term.field(
                                                                                                "workflowId")
                                                                                        .value(
                                                                                                "cleanup")))
                                                .refresh(true));
        assertEquals(Long.valueOf(1), deleted.deleted());
        assertEquals(List.of(), deleted.failures());
        assertEquals(
                generated.id(),
                write().delete(
                                request ->
                                        request.index("conductor_task")
                                                .id(generated.id())
                                                .refresh(Refresh.True))
                        .id());
    }
}
