# Load testing and progressive delivery

This phase adds the two things that turn "it deploys" into "it deploys safely": a load
generator that can state whether the system meets its SLOs, and a canary gate that stops a
deploy that does not.

The honest headline is in the second half of this document. Building the gate was
straightforward. **Proving the gate worked took six bug fixes, every one of which was a
case of the gate failing open** — reporting success while a build with a measured 51.6%
error rate sailed through. That result is the actual deliverable of this phase.

---

## Load model: open, not closed

k6 runs every scenario under `constant-arrival-rate`, not `constant-vus`.

The distinction matters more than it looks. A closed model (`constant-vus`) holds a fixed
number of virtual users, each of which waits for its response before issuing the next
request. When the system slows down, the load *automatically backs off* — throughput falls,
queues stay short, and the test reports a gentle latency increase where production would
have reported an outage. A closed-model test cannot reproduce congestive collapse, because
it is structurally incapable of overloading anything.

An open model issues requests at a fixed rate regardless of whether the previous ones have
come back. That is how real users behave: nobody pauses their Twitter habit because the
timeline is slow. If the service cannot keep up, the arrival rate exceeds the service rate,
queues grow, and the test shows the cliff.

The cost is that k6 must be able to allocate VUs fast enough to sustain the rate, which is
why every scenario sets `preAllocatedVUs` and `maxVUs` as functions of the target rate
rather than leaving them at their defaults. A `dropped_iterations` count above zero means
the *generator* failed, not the system, and any result with one is void.

## Why k6 runs in-cluster

`scripts/load-test.sh` renders a Kubernetes `Job` and waits for it, rather than running the
k6 binary on the laptop against a port-forward.

A port-forward is a single TCP connection proxied through the API server. It serialises,
it adds a hop with its own buffering, and it is the most likely component to become the
bottleneck — which produces the worst possible outcome for a load test: a number that
describes the measuring instrument. Running in-cluster puts the generator on the same
network as the services, hitting the real `ClusterIP`, exercising the real kube-proxy path
and the real NetworkPolicies.

It also means the same script works unchanged against kind and against EKS, which is the
whole point of the three-tier model.

Two portability notes that cost real time:

- **`wait -n` does not exist in bash 3.2**, which is what macOS ships. The original
  implementation raced two `kubectl wait` calls against each other; it had to be rewritten
  as a poll loop, which turned out better anyway because the loop also samples the HPA.
- **Kubernetes 1.31 inserts a `SuccessCriteriaMet` condition before `Complete`.** Reading
  `.status.conditions[0].type` therefore polls forever on a Job that finished seven minutes
  earlier. The read is now `?(@.status=="True")` filtered and matched against
  `^(Complete|Failed)$`.

## SLOs are declared once

`load/lib/slo.js` is the single definition of the latency and error budgets. The k6
thresholds import it, and the same numbers are the basis for the Prometheus alert rules and
the canary analysis conditions.

The alternative — a threshold in the k6 script, a different number in the alert rule, and a
third in the `AnalysisTemplate` — is the normal state of affairs and is indefensible. It
means the load test can pass while the alert fires, or the canary can promote a build the
alert will page for ten minutes later. Whatever the numbers are, they have to be the same
numbers.

## Measured results

| Scenario | Requests | Failures | Timeline p95 | HPA |
|---|---|---|---|---|
| smoke | 4 | 0 | — | — |
| ramp 12 rps / 3 m | 4877 | 0 | 37 ms (budget 400 ms) | no scale-out, CPU peaked ~41% of a 60% target |
| ramp 30 rps / 2 m | 8385 | 0 | 21 ms | **2 → 3 → 5**, CPU peaked 105%/60% |

The 30 rps run is the useful one: it is the first that crossed the HPA target and
demonstrated scale-out to `maxReplicas` and back. p95 *fell* between the two runs, which is
JIT warm-up, not an improvement — a reminder that short load tests measure the JVM's opinion
of itself as much as the system.

**These numbers are not capacity figures.** The generator, the control plane, the emulated
AWS services and the application all share one laptop. Above 30 rps the binding constraint
stopped being the application at all: LocalStack was `OOMKilled` at its 1 Gi limit, which
destroyed every DynamoDB table and made the services answer `ResourceNotFoundException` at
a 90% rate. See gap S38.

## Progressive delivery

The service chart emits either a `Deployment` or an Argo Rollouts `Rollout` from a single
template, switched by `rollout.enabled`. One template, not two, so the pod spec cannot
drift between the two paths — a two-template arrangement guarantees that one of them is
subtly wrong and that nobody notices until the day it is used.

The canary steps are `setWeight`/`pause` pairs with analysis attached. The HPA targets the
`Rollout` through the scale subresource, which works: after roughly 75 seconds it stops
reporting `<unknown>` and drives real replica counts.

Two approximations are worth naming. There is no traffic-routing provider in this tier, so
`setWeight: 20` means *20% of pods*, not 20% of requests — close enough with five replicas
and even load balancing, and meaningfully wrong with two. And analysis measures the whole
`Service`, because without a mesh no label distinguishes canary pods from stable ones.

That second one has teeth. It creates a genuine catch-22: if the stable set is broken, the
aggregate error rate stays bad, and **the healthy replacement fails a gate that is
measuring the very thing it would fix**. This happened, took the gateway to 100% 5xx for
about fifteen minutes, and needed `promote --full` to break out of. Argo latches
`status.abort: true` and a corrected spec alone will not clear it.

---

## The part that matters: six ways the gate failed open

`scripts/rollback-drill.sh` deploys a deliberately broken build and asserts that the canary
rejects it. It also runs in `--healthy` mode, deploying an unchanged configuration and
asserting that it *promotes* — because a gate that rejects everything is as useless as one
that accepts everything, and a one-sided test cannot tell them apart.

Writing the drill took an afternoon. Making it pass took six fixes, and each fix revealed
that the gate had, until that moment, been incapable of failing:

1. **The query matched no time series.** The selector was `service="gateway"`; the scrape
   label is `app`. An empty Prometheus result is scored `Successful` by Argo, so the gate
   promoted everything. Fixed with the right label and `len(result) > 0` on both
   conditions.
2. **The analysis was terminated before it could object.** Background analysis is killed
   when the rollout promotes, and a terminated run is logged as
   `Successful: Run Terminated`. Fixed by adding an **inline** analysis step, which blocks.
3. **A NetworkPolicy blocked the controller from Prometheus.** Combined with (1), the gate
   could neither reach its data nor complain about not having any.
4. **The numerator was empty on healthy builds.** A service with no 5xx has no 5xx series,
   so `sum(rate(5xx))/sum(rate(all))` is empty — not zero. Fixed as
   `(sum(rate(5xx)) or vector(0)) / sum(rate(all))`. The denominator is deliberately *not*
   `clamp_min`-ed: clamping manufactures a denominator from nothing and converts "no metrics
   at all" into a confident `0`. Leaving it unguarded makes total metric absence propagate
   to empty, which now fails closed.
5. **The first measurement described the previous deploy.** `rate(...[2m])` evaluated at
   t=0 straddles the errors of the version being replaced, and with `failureLimit: 0` that
   aborts known-good builds. Fixed with `initialDelay >= lookback` and by starting steady
   traffic *before* the mutation.
6. **The p95 metric did not exist.** Spring Boot publishes no histogram buckets unless
   asked, so `histogram_quantile()` had been silently empty since the day it was written.

And a seventh, found only because the first six were fixed: **the fault was on a path the
load generator never exercised.** The drill broke `USER_SERVICE_URL`, but `steady.js`
enrols each VU once and caches the token, so after warm-up nothing calls user-service again.
The canary measured an error rate of exactly `0.0` — correctly, and uselessly. The fault now
targets `TIMELINE_SERVICE_URL`, which is ~80% of steady traffic.

An eighth, in the drill's own assertions: the "an AnalysisRun failed" check matched *any*
failed run in the namespace, including leftovers from earlier drills. It therefore reported
PASS during a run in which every `AnalysisRun` was `Successful` and the broken build had
been promoted. The check is now scoped to runs created after the drill started.

The pattern is the point. Not one of these was caught by CI, by `helm lint`, by the unit
tests, or by the smoke test — all of which were green throughout. **Every single defect
biased the system toward saying yes.** That is not a coincidence: a control has one failure
mode that is loud (rejecting good builds, which someone complains about within minutes) and
one that is silent (accepting bad ones, which nobody notices until production). Only the
loud one gets found by accident.

The static half of the control is `scripts/lib/validate-rollout.py`, which asserts the
structural invariants that the drill would otherwise have to discover at runtime: exactly
one workload kind is rendered, the HPA's `scaleTargetRef` matches it, `maxReplicas >
minReplicas`, the `AnalysisTemplate` exists and is referenced, canary pods carry the scrape
annotation, `failureLimit` is `0`, every division is guarded by `or vector(0)`, and every
`successCondition` contains `len(result)`. It was itself negative-tested against four
deliberately mutated manifests before being trusted.

## Running it

```sh
make load-smoke              # 4-request sanity check
make load-test               # ramped load with SLO thresholds
make rollback-drill-control  # deploy an unchanged build, assert it promotes
make rollback-drill          # deploy a broken build, assert it is rejected
```

Both drills must pass. Either one alone proves nothing.
