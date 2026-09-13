# Optional Elasticsearch journal client profile

The ES8 client can attach stable logical-write IDs for an external durability gateway. The profile is disabled by default: `conductor.elasticsearch.journalGeneration` is unset, so existing deployments keep their current direct-Elasticsearch behavior.

To prepare a client for a separately validated gateway, set a positive `conductor.elasticsearch.journalGeneration`, `conductor.elasticsearch.autoIndexManagementEnabled=false`, and `conductor.elasticsearch.refreshOnWrite=true`. Startup rejects an unsafe combination. Metadata and aliases must already exist; disabling Conductor index management does not disable an existing Elasticsearch ILM policy.

Each synchronous write receives one `X-OpenSensor-Request-ID` before its RetryTemplate loop and retains it across retries. Separate calls receive distinct IDs even when their payloads are identical. The base REST client also sends `X-OpenSensor-Generation` on reads. Typed client clones preserve other transport headers. The profile prevents the buffered BulkIngester path from bypassing request identity; direct task-log bulk requests remain supported.

These headers do not authenticate a caller or establish durability by themselves. The external gateway must authenticate and fence the writer, durably persist acknowledged effects, preserve partial bulk outcomes, and resolve ambiguous requests before promotion. The current separate prototype has unresolved session-loss fencing, replay-finalization and transient-retry concerns. Enabling this profile is not authorization to route production traffic through it.

The manual `OpenSensor journal client validation` workflow runs formatting checks, ES8 tests (including a real pinned Elasticsearch9.5.3 compatibility test), and builds an ES8-backed server JAR as a GitHub artifact. It has no registry credentials, publish step, Kubernetes access or deployment action. Passing these tests validates client behavior; it does not certify the separate gateway or zero-loss production failover.
