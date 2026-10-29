import { scrubEvent } from "./sentry";

test("workflow records are removed while useful error frames survive", () => {
  const event = {
    user: { email: "private@example.invalid" },
    request: { data: { workflowInput: "private" } },
    extra: { task: "private" },
    breadcrumbs: [{ message: "private" }],
    message: "private workflow payload",
    transaction: "/workflow/private-record",
    spans: [{ op: "http.client", description: "GET /api/private-record", data: { url: "private" } }],
    exception: { values: [{ type: "TypeError", value: "private workflow payload", stacktrace: { frames: [{ filename: "src/App.js", lineno: 7 }] } }] },
    release: "conductor-web@test",
  };
  const result = scrubEvent(event);
  expect(JSON.stringify(result)).not.toContain("private");
  expect(result.exception.values[0].stacktrace.frames[0].filename).toBe("src/App.js");
  expect(result.release).toBe("conductor-web@test");
});
