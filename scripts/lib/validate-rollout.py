#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Structural assertions on progressive delivery and autoscaling.

These are the failure modes that render, apply, and pass every schema check
while doing nothing:

  * an HPA whose scaleTargetRef points at a kind the chart no longer emits —
    the object exists, ``kubectl get hpa`` lists it, and it reports
    ``FailedGetScale`` forever;
  * a Rollout referencing an AnalysisTemplate that was never rendered — the
    rollout blocks at the first analysis step with an error nobody is watching
    for;
  * an analysis with a non-zero failureLimit, which is a gate that tolerates
    the thing it exists to catch;
  * a canary on a workload with no metrics — nothing to analyse, so the
    analysis is permanently inconclusive.

Invoked as: validate-rollout.py <rendered.yaml> <env> <service>
Prints one ``OK``/``FAIL`` line per assertion; exits non-zero on any failure.
"""

import re
import sys

import yaml

PROBLEMS: list[str] = []
CHECKS = 0


def check(condition: bool, message: str) -> None:
    global CHECKS
    CHECKS += 1
    if not condition:
        PROBLEMS.append(message)


def main() -> int:
    global CHECKS
    rendered, env, service = sys.argv[1], sys.argv[2], sys.argv[3]
    with open(rendered, encoding="utf-8") as handle:
        docs = [d for d in yaml.safe_load_all(handle) if d]

    by_kind: dict[str, list[dict]] = {}
    for doc in docs:
        by_kind.setdefault(doc.get("kind", "?"), []).append(doc)

    rollouts = by_kind.get("Rollout", [])
    deployments = by_kind.get("Deployment", [])
    hpas = by_kind.get("HorizontalPodAutoscaler", [])
    templates = {t["metadata"]["name"] for t in by_kind.get("AnalysisTemplate", [])}

    # Exactly one workload. Emitting both a Deployment and a Rollout with the
    # same selector gives two controllers fighting over one set of pods.
    check(
        len(rollouts) + len(deployments) == 1,
        f"{env}/{service}: expected exactly one workload, "
        f"got {len(deployments)} Deployment(s) and {len(rollouts)} Rollout(s)",
    )

    workload_kind = "Rollout" if rollouts else "Deployment"

    for hpa in hpas:
        ref = hpa["spec"]["scaleTargetRef"]
        check(
            ref.get("kind") == workload_kind,
            f"{env}/{service}: HPA targets {ref.get('kind')} but the chart renders "
            f"a {workload_kind} — the HPA will report FailedGetScale and never scale",
        )
        check(
            ref.get("name") == service,
            f"{env}/{service}: HPA targets '{ref.get('name')}', not '{service}'",
        )
        # An HPA whose floor equals its ceiling is a fixed replica count with
        # extra steps, and it reads in review as though autoscaling is enabled.
        check(
            hpa["spec"]["maxReplicas"] > hpa["spec"].get("minReplicas", 1),
            f"{env}/{service}: HPA maxReplicas is not above minReplicas — it cannot scale out",
        )

    for rollout in rollouts:
        analysis = rollout["spec"].get("strategy", {}).get("canary", {}).get("analysis")
        check(
            analysis is not None,
            f"{env}/{service}: Rollout has no analysis — the canary pauses and waits "
            f"for a human, which is a slow deploy rather than a gate",
        )
        if not analysis:
            continue
        for ref in analysis.get("templates", []):
            check(
                ref["templateName"] in templates,
                f"{env}/{service}: Rollout references AnalysisTemplate "
                f"'{ref['templateName']}', which this chart does not render",
            )
        # Analysis needs traffic it can see. A canary on a workload that is not
        # scraped produces an empty query result forever.
        scrape = (
            rollout["spec"]["template"]["metadata"]
            .get("annotations", {})
            .get("prometheus.io/scrape")
        )
        check(
            scrape == "true",
            f"{env}/{service}: Rollout is analysed against Prometheus but the pod is "
            f"not annotated for scraping — every query returns no data",
        )

    for template in by_kind.get("AnalysisTemplate", []):
        for metric in template["spec"]["metrics"]:
            check(
                metric.get("failureLimit", 0) == 0,
                f"{env}/{service}: metric '{metric['name']}' has "
                f"failureLimit={metric.get('failureLimit')} — a gate that tolerates "
                f"failures promotes builds that are broken some of the time",
            )
            check(
                "successCondition" in metric or "failureCondition" in metric,
                f"{env}/{service}: metric '{metric['name']}' has no condition — it is "
                f"collected and never evaluated",
            )
            query = metric.get("provider", {}).get("prometheus", {}).get("query", "")
            # Label matchers contain slashes (`uri!~"/actuator.*"`), so a naive
            # search for "/" flags every query as a division. Strip string
            # literals first and look for a real binary operator.
            bare = re.sub(r'"[^"]*"', '""', query)
            if "/" in bare and "or vector(0)" not in bare:
                PROBLEMS.append(
                    f"{env}/{service}: metric '{metric['name']}' divides without "
                    f"an `or vector(0)` guard on the numerator — a service with "
                    f"zero errors matches no series, so the ratio is empty and "
                    f"the gate reports no data on a perfectly healthy build"
                )
            CHECKS += 1

            # An empty Prometheus result makes `result[0] < x` vacuously true,
            # so a query whose selector matches nothing promotes every build
            # while reporting success. Observed for real: see gap register #32.
            cond = metric.get("successCondition", "")
            if cond and "len(result)" not in cond:
                PROBLEMS.append(
                    f"{env}/{service}: metric '{metric['name']}' has a "
                    f"successCondition that does not check len(result) — a query "
                    f"matching no series would pass the gate unmeasured"
                )
            CHECKS += 1

    for problem in PROBLEMS:
        print(f"FAIL {problem}")
    if PROBLEMS:
        return 1
    print(f"OK {CHECKS} assertion(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
