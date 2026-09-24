// SPDX-License-Identifier: MIT
//
// Thresholds, expressed once so every scenario agrees on what "good" means.
//
// These are the same numbers as the Prometheus SLOs in
// `deploy/charts/observability/templates/prometheus-rules.yaml`. Keeping two
// definitions of the objective is how a load test comes to pass while the
// burn-rate alert fires — the test and the alert disagree about the target and
// both look authoritative.
//
// They are *targets*, not observations. On Tier L the whole cluster is three
// containers on one laptop, so the numbers below are intentionally generous;
// `docs/06-load-and-delivery.md` records what was actually measured and where
// the laptop, rather than the application, is the limit.

export const SLO = {
  // 99% of requests succeed. One nine short of the usual 99.9% because the
  // sandbox has no redundancy: a single pod eviction is a visible outage, and a
  // threshold that a known-good system fails is worse than no threshold.
  availability: 0.99,

  // Read path. A timeline read is a Redis hit or a single DynamoDB query.
  timelineP95Ms: 400,

  // Write path. A post is a conditional DynamoDB write plus a stream append,
  // and it is the operation a user waits on, so it gets its own budget.
  postP95Ms: 800,

  // Registration does bcrypt (deliberately slow) and is not on any hot path.
  registerP95Ms: 2000,
};

/**
 * k6 thresholds. `abortOnFail` is off on purpose: a run that stops at the first
 * breach hides how bad things got, and the point of a load test is the shape of
 * the degradation, not the instant it began.
 */
export const thresholds = {
  flow_failures: [`rate<${(1 - SLO.availability).toFixed(4)}`],
  flow_timeline_duration: [`p(95)<${SLO.timelineP95Ms}`],
  flow_post_duration: [`p(95)<${SLO.postP95Ms}`],
  flow_register_duration: [`p(95)<${SLO.registerP95Ms}`],
  // Guards against the failure mode where every request 500s instantly and the
  // latency thresholds all pass because errors are fast.
  checks: ['rate>0.99'],
};
