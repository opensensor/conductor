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

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.http.HttpHost;
import org.conductoross.conductor.es8.config.ElasticSearchProperties;
import org.conductoross.conductor.es8.config.ElasticSearchV8Configuration;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RestClient;
import org.junit.After;
import org.junit.Test;
import org.springframework.retry.support.RetryTemplate;

import com.netflix.conductor.common.metadata.tasks.TaskExecLog;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class JournalRequestIdentityTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<RestClient> clients = new ArrayList<>();
    private final List<ElasticSearchRestDAOV8> daos = new ArrayList<>();
    private HttpServer server;

    @After
    public void close() throws Exception {
        for (ElasticSearchRestDAOV8 dao : daos) {
            var shutdown = ElasticSearchRestDAOV8.class.getDeclaredMethod("shutdown");
            shutdown.setAccessible(true);
            shutdown.invoke(dao);
        }
        for (RestClient client : clients) {
            client.close();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    private ElasticSearchProperties profile() {
        ElasticSearchProperties properties = new ElasticSearchProperties();
        properties.setJournalGeneration(7L);
        properties.setAutoIndexManagementEnabled(false);
        properties.setRefreshOnWrite(true);
        return properties;
    }

    private void start(com.sun.net.httpserver.HttpHandler handler) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler);
        server.start();
    }

    private void reply(HttpExchange exchange, int status, Object response)
            throws java.io.IOException {
        byte[] body = mapper.writeValueAsBytes(response);
        exchange.getResponseHeaders().set("X-Elastic-Product", "Elasticsearch");
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        if (exchange.getRequestMethod().equals("HEAD")) {
            exchange.sendResponseHeaders(status, -1);
        } else {
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
        }
        exchange.close();
    }

    private Map<String, Object> indexed() {
        return Map.of(
                "_index",
                "conductor_task-000001",
                "_id",
                "task-id",
                "_version",
                1,
                "result",
                "created",
                "_shards",
                Map.of("total", 1, "successful", 1, "failed", 0),
                "_seq_no",
                0,
                "_primary_term",
                1);
    }

    @Test
    public void disabledProfileReturnsOriginalClientAndAddsNoIdentity() throws Exception {
        start(exchange -> reply(exchange, 200, Map.of()));
        RestClient rest =
                RestClient.builder(new HttpHost("127.0.0.1", server.getAddress().getPort()))
                        .build();
        clients.add(rest);
        ElasticsearchClient original =
                new ElasticsearchClient(new RestClientTransport(rest, new JacksonJsonpMapper()));
        ElasticSearchProperties properties = new ElasticSearchProperties();
        assertNull(properties.getJournalGeneration());
        assertSame(original, JournalRequestIdentity.forWrite(original, properties));
    }

    @Test
    public void identitySurvivesTransportAndApplicationRetriesAndPreservesHeaders()
            throws Exception {
        List<String> ids = new CopyOnWriteArrayList<>();
        List<String> bodies = new CopyOnWriteArrayList<>();
        List<String> generations = new CopyOnWriteArrayList<>();
        List<String> custom = new CopyOnWriteArrayList<>();
        List<String> auth = new CopyOnWriteArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        start(
                exchange -> {
                    ids.add(exchange.getRequestHeaders().getFirst("X-OpenSensor-Request-ID"));
                    generations.add(
                            exchange.getRequestHeaders().getFirst("X-OpenSensor-Generation"));
                    custom.add(exchange.getRequestHeaders().getFirst("X-Custom-Transport"));
                    auth.add(exchange.getRequestHeaders().getFirst("Authorization"));
                    bodies.add(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    int attempt = calls.incrementAndGet();
                    if (attempt <= 2) {
                        reply(
                                exchange,
                                attempt == 1 ? 503 : 500,
                                Map.of(
                                        "error",
                                        Map.of("type", "test_retry", "reason", "isolated fixture"),
                                        "status",
                                        attempt == 1 ? 503 : 500));
                    } else {
                        reply(exchange, 201, indexed());
                    }
                });
        int port = server.getAddress().getPort();
        RestClient rest =
                RestClient.builder(new HttpHost("127.0.0.1", port), new HttpHost("localhost", port))
                        .build();
        clients.add(rest);
        ElasticsearchClient source =
                new ElasticsearchClient(new RestClientTransport(rest, new JacksonJsonpMapper()))
                        .withTransportOptions(
                                options ->
                                        options.setHeader("X-Custom-Transport", "retained")
                                                .setHeader(
                                                        "Authorization",
                                                        "Bearer synthetic-test-only"));
        ElasticsearchClient request = JournalRequestIdentity.forWrite(source, profile());
        AtomicInteger applicationAttempts = new AtomicInteger();
        RetryTemplate.builder()
                .maxAttempts(3)
                .fixedBackoff(1)
                .build()
                .execute(
                        context -> {
                            applicationAttempts.incrementAndGet();
                            return request.index(
                                    index ->
                                            index.index("conductor_task")
                                                    .id("task-id")
                                                    .document(Map.of("number", 1)));
                        });
        assertEquals(3, calls.get());
        assertTrue(
                "A 500 must reach the application RetryTemplate", applicationAttempts.get() >= 2);
        assertNotNull(ids.getFirst());
        assertEquals(1, ids.stream().distinct().count());
        assertEquals(1, bodies.stream().distinct().count());
        assertEquals(List.of("7", "7", "7"), generations);
        assertEquals(List.of("retained", "retained", "retained"), custom);
        assertEquals(
                List.of(
                        "Bearer synthetic-test-only",
                        "Bearer synthetic-test-only",
                        "Bearer synthetic-test-only"),
                auth);
        JournalRequestIdentity.forWrite(source, profile())
                .index(
                        index ->
                                index.index("conductor_task")
                                        .id("task-id")
                                        .document(Map.of("number", 1)));
        assertNotEquals(
                "Separate identical calls remain distinct operations",
                ids.getFirst(),
                ids.getLast());
    }

    @Test
    public void actualDaoLogBulkUsesOneIdentityAcrossItsRetryTemplate() throws Exception {
        List<String> ids = new CopyOnWriteArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        start(
                exchange -> {
                    ids.add(exchange.getRequestHeaders().getFirst("X-OpenSensor-Request-ID"));
                    exchange.getRequestBody().readAllBytes();
                    if (calls.incrementAndGet() == 1) {
                        reply(
                                exchange,
                                500,
                                Map.of(
                                        "error",
                                        Map.of("type", "test_retry", "reason", "retry"),
                                        "status",
                                        500));
                    } else {
                        reply(
                                exchange,
                                200,
                                Map.of(
                                        "took",
                                        1,
                                        "errors",
                                        false,
                                        "items",
                                        List.of(
                                                Map.of(
                                                        "index",
                                                        Map.of(
                                                                "_index",
                                                                "conductor_task_log-000001",
                                                                "_id",
                                                                "assigned",
                                                                "status",
                                                                201)))));
                    }
                });
        ElasticSearchProperties properties = profile();
        properties.setUrl("http://127.0.0.1:" + server.getAddress().getPort());
        ElasticSearchRestDAOV8 dao =
                new ElasticSearchRestDAOV8(
                        new ElasticSearchV8Configuration().elasticRestClientBuilder(properties),
                        RetryTemplate.builder().maxAttempts(2).fixedBackoff(1).build(),
                        properties,
                        mapper);
        daos.add(dao);
        TaskExecLog log = new TaskExecLog();
        log.setTaskId("task-id");
        log.setLog("synthetic log");
        dao.addTaskExecutionLogs(List.of(log));
        assertEquals(2, ids.size());
        assertNotNull(ids.getFirst());
        assertEquals(ids.getFirst(), ids.getLast());
        dao.addTaskExecutionLogs(List.of(log));
        assertNotEquals(ids.getFirst(), ids.getLast());
    }

    @Test
    public void generationReachesHealthAliasHeadAndSearchReadsWithoutWriteIdentity()
            throws Exception {
        List<String> generations = new CopyOnWriteArrayList<>();
        List<String> identities = new ArrayList<>();
        start(
                exchange -> {
                    generations.add(
                            exchange.getRequestHeaders().getFirst("X-OpenSensor-Generation"));
                    identities.add(
                            exchange.getRequestHeaders().getFirst("X-OpenSensor-Request-ID"));
                    reply(exchange, 200, Map.of());
                });
        ElasticSearchProperties properties = profile();
        properties.setUrl("http://127.0.0.1:" + server.getAddress().getPort());
        RestClient rest =
                new ElasticSearchV8Configuration().elasticRestClientBuilder(properties).build();
        clients.add(rest);
        rest.performRequest(new Request("GET", "/_cluster/health"));
        rest.performRequest(new Request("HEAD", "/_alias/conductor_task"));
        rest.performRequest(new Request("POST", "/conductor_task/_search"));
        assertEquals(List.of("7", "7", "7"), generations);
        assertTrue(identities.stream().allMatch(java.util.Objects::isNull));
    }

    @Test
    public void typedBulkClientParsesNormalizedMissingIndexAndMixedErrors() throws Exception {
        start(
                exchange ->
                        reply(
                                exchange,
                                200,
                                Map.of(
                                        "took",
                                        0,
                                        "errors",
                                        true,
                                        "items",
                                        List.of(
                                                Map.of(
                                                        "index",
                                                        Map.of(
                                                                "_index",
                                                                "conductor_task-000099",
                                                                "_id",
                                                                "missing-index",
                                                                "status",
                                                                404,
                                                                "error",
                                                                Map.of(
                                                                        "type",
                                                                        "index_not_found_exception",
                                                                        "reason",
                                                                        "Concrete index must be pre-provisioned"))),
                                                Map.of(
                                                        "index",
                                                        Map.of(
                                                                "_index",
                                                                "conductor_task-000001",
                                                                "_id",
                                                                "valid",
                                                                "status",
                                                                201))))));
        RestClient rest =
                RestClient.builder(new HttpHost("127.0.0.1", server.getAddress().getPort()))
                        .build();
        clients.add(rest);
        ElasticsearchClient client =
                new ElasticsearchClient(new RestClientTransport(rest, new JacksonJsonpMapper()));
        BulkResponse response =
                client.bulk(
                        bulk ->
                                bulk.operations(
                                                op ->
                                                        op.index(
                                                                index ->
                                                                        index.index(
                                                                                        "conductor_task-000099")
                                                                                .id("missing-index")
                                                                                .document(
                                                                                        Map.of(
                                                                                                "number",
                                                                                                1))))
                                        .operations(
                                                op ->
                                                        op.index(
                                                                index ->
                                                                        index.index(
                                                                                        "conductor_task-000001")
                                                                                .id("valid")
                                                                                .document(
                                                                                        Map.of(
                                                                                                "number",
                                                                                                2)))));
        assertTrue(response.errors());
        assertEquals("conductor_task-000099", response.items().getFirst().index());
        assertEquals(404, response.items().getFirst().status());
        assertEquals(201, response.items().getLast().status());
    }

    @Test
    public void unsafeJournalConfigurationFailsBeforeAnyConnection() {
        ElasticSearchV8Configuration configuration = new ElasticSearchV8Configuration();
        ElasticSearchProperties properties = profile();
        properties.setAutoIndexManagementEnabled(true);
        assertThrows(
                IllegalStateException.class,
                () -> configuration.elasticRestClientBuilder(properties));
        properties.setAutoIndexManagementEnabled(false);
        properties.setRefreshOnWrite(false);
        assertThrows(
                IllegalStateException.class,
                () -> configuration.elasticRestClientBuilder(properties));
        properties.setRefreshOnWrite(true);
        properties.setJournalGeneration(0L);
        assertThrows(
                IllegalStateException.class,
                () -> configuration.elasticRestClientBuilder(properties));
    }
}
