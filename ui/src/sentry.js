import * as Sentry from "@sentry/react";

const dsn = process.env.REACT_APP_SENTRY_DSN;

export function scrubEvent(event) {
  delete event.user;
  delete event.request;
  delete event.extra;
  delete event.breadcrumbs;
  delete event.message;
  event.exception?.values?.forEach((exception) => {
    exception.value = "[Filtered]";
  });
  // UI paths and network spans can contain workflow IDs or query parameters.
  if (event.transaction) event.transaction = "conductor-ui";
  event.spans?.forEach((span) => {
    delete span.data;
    span.description = span.op || "browser";
  });
  return event;
}

if (dsn) {
  Sentry.init({
    dsn,
    environment: process.env.REACT_APP_SENTRY_ENVIRONMENT || "production",
    release: process.env.REACT_APP_SENTRY_RELEASE,
    sendDefaultPii: false,
    integrations: [Sentry.browserTracingIntegration()],
    tracesSampleRate: 0.1,
    tracePropagationTargets: [],
    beforeSend: scrubEvent,
    beforeSendTransaction: scrubEvent,
    beforeBreadcrumb: () => null,
  });
}

export const ErrorBoundary = Sentry.ErrorBoundary;
