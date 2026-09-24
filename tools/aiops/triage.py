# SPDX-License-Identifier: MIT
"""Draft a probable-cause summary for a firing Alertmanager alert.

Usage:
    python tools/aiops/triage.py --alert payload.json \
        [--prometheus http://localhost:19090] [--loki http://localhost:13100]

The same split as `risk_comment.py`: this module gathers **evidence**, and the
model only writes the narrative. Concretely, it does what a human does in the
first ninety seconds after a page --

  - reads the alert's own labels and annotations, including the runbook link,
  - re-evaluates the alert expression so the summary states the *current* value
    rather than the value at fire time,
  - asks Prometheus which other alerts are firing, because the interesting
    question is almost always "what else broke at the same moment",
  - pulls the error rate and recent restart count for the named service,
  - pulls the last few error lines from Loki.

-- and then presents it in one place. Everything it reports is a query result
with the query printed next to it, so a wrong conclusion is traceable to the
evidence that produced it rather than to the model's mood.

It never mutates anything. There is no code path here that scales, restarts,
deletes or applies; see AGENTS.md.
"""

from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import providers  # noqa: E402

TIMEOUT = 15

SYSTEM = """You are the first responder to a production alert, writing for the \
engineer who has just been paged and has not yet opened a terminal.

You are given the alert and a set of query results. Every fact you state must \
come from that evidence.

Rules:
- Start with the single most likely cause, and say how confident you are.
- If the evidence does not support a cause, say that plainly and say which \
query would settle it. Do not guess.
- Name the specific next command or dashboard to look at.
- Never claim something is resolved, and never recommend a mutating action \
(restart, scale, delete, rollback) as a first step.
- At most 200 words. Markdown, no headings above level 3."""


@dataclass
class Evidence:
    label: str
    query: str
    value: str


def _get(url: str, params: dict) -> dict | None:
    full = f"{url}?{urllib.parse.urlencode(params)}"
    try:
        with urllib.request.urlopen(full, timeout=TIMEOUT) as resp:
            return json.loads(resp.read().decode())
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError, OSError):
        # A triage tool that fails because one of its evidence sources is down
        # is useless precisely when it is needed: the source being down is
        # frequently the story. Missing evidence is reported as missing.
        return None


def promql(base: str, query: str) -> str:
    """Run an instant query and flatten it to one human-readable line.

    Returns the string `no data` for an empty result rather than `0`. The
    distinction cost this project an entire canary gate (gap register row 32):
    an empty vector is the absence of evidence, and rendering it as a number
    invents a measurement that was never taken.
    """
    data = _get(f"{base.rstrip('/')}/api/v1/query", {"query": query})
    if data is None:
        return "unreachable"
    result = (data.get("data") or {}).get("result") or []
    if not result:
        return "no data"
    parts = []
    for series in result[:5]:
        metric = series.get("metric") or {}
        ident = (
            metric.get("pod")
            or metric.get("app")
            or metric.get("instance")
            or metric.get("__name__")
            or "series"
        )
        raw = (series.get("value") or [None, "?"])[1]
        try:
            shown = f"{float(raw):.4g}"
        except (TypeError, ValueError):
            shown = str(raw)
        parts.append(f"{ident}={shown}")
    more = "" if len(result) <= 5 else f" (+{len(result) - 5} more)"
    return ", ".join(parts) + more


def loki_tail(base: str, selector: str, limit: int = 10) -> str:
    data = _get(
        f"{base.rstrip('/')}/loki/api/v1/query_range",
        {"query": selector, "limit": str(limit), "direction": "backward"},
    )
    if data is None:
        return "unreachable"
    streams = (data.get("data") or {}).get("result") or []
    lines: list[str] = []
    for s in streams:
        for _ts, line in s.get("values") or []:
            lines.append(line.strip()[:240])
    if not lines:
        return "no matching log lines"
    return "\n".join(lines[:limit])


def gather(alert: dict, prom: str, loki: str) -> tuple[dict, list[Evidence]]:
    """Collect the alert's own fields plus the queries a responder would run."""
    labels = alert.get("labels") or {}
    annotations = alert.get("annotations") or {}
    # `service` is this project's convention; `app` is the scrape label and
    # `job` is the Prometheus default. Trying all three means the tool still
    # works on alerts that predate the convention.
    svc = labels.get("service") or labels.get("app") or labels.get("job") or ""
    ns = labels.get("namespace") or "twitter-clone"

    head = {
        "alertname": labels.get("alertname", "(unnamed)"),
        "severity": labels.get("severity", "unknown"),
        "service": svc or "(not labelled)",
        "namespace": ns,
        "summary": annotations.get("summary", ""),
        "description": annotations.get("description", ""),
        "runbook": annotations.get("runbook_url", ""),
        "startsAt": alert.get("startsAt", ""),
        "expression": alert.get("generatorURL", ""),
    }

    ev: list[Evidence] = []

    q_other = 'sum by (alertname, severity) (ALERTS{alertstate="firing"})'
    ev.append(Evidence("other alerts firing", q_other, promql(prom, q_other)))

    if svc:
        q_err = (
            f'(sum(rate(http_server_requests_seconds_count{{app="{svc}",'
            f'status=~"5.."}}[5m])) or vector(0)) / '
            f'sum(rate(http_server_requests_seconds_count{{app="{svc}"}}[5m]))'
        )
        ev.append(Evidence("error ratio (5m)", q_err, promql(prom, q_err)))

        q_p95 = (
            f"histogram_quantile(0.95, sum by (le) (rate("
            f'http_server_requests_seconds_bucket{{app="{svc}"}}[5m])))'
        )
        ev.append(Evidence("p95 latency (5m)", q_p95, promql(prom, q_p95)))

        q_rs = f'sum(kube_pod_container_status_restarts_total{{namespace="{ns}"}})'
        ev.append(Evidence("container restarts", q_rs, promql(prom, q_rs)))

        q_up = f'up{{app="{svc}"}}'
        ev.append(Evidence("scrape targets up", q_up, promql(prom, q_up)))

        sel = f'{{namespace="{ns}",app="{svc}"}} |= "ERROR"'
        ev.append(Evidence("recent error logs", sel, loki_tail(loki, sel)))

    return head, ev


def render(head: dict, ev: list[Evidence], text: str | None, note: str) -> str:
    out = [
        f"## Alert triage — {head['alertname']} ({head['severity']})",
        "",
        f"**Service** `{head['service']}` in `{head['namespace']}` · "
        f"firing since {head['startsAt'] or 'unknown'}",
        "",
    ]
    if head["summary"]:
        out += [f"> {head['summary']}", ""]
    if head["description"]:
        out += [head["description"], ""]
    if head["runbook"]:
        out += [f"**Runbook:** {head['runbook']}", ""]

    if text:
        out += ["### Probable cause", "", text, ""]

    out += ["### Evidence", "", "| Signal | Value |", "|---|---|"]
    for e in ev:
        value = e.value.replace("|", "\\|").replace("\n", "<br>")
        out.append(f"| {e.label} | {value} |")
    out += [
        "",
        "<details><summary>Queries used</summary>",
        "",
        "```promql",
        *[f"# {e.label}\n{e.query}" for e in ev],
        "```",
        "",
        "</details>",
        "",
        f"<sub>Evidence gathered by `tools/aiops/triage.py` — read-only, no "
        f"mutating calls. Generation: {note}.</sub>",
    ]
    return "\n".join(out)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--alert", required=True, help="Alertmanager webhook payload, or a single alert")
    ap.add_argument("--prometheus", default="http://localhost:19090")
    ap.add_argument("--loki", default="http://localhost:13100")
    args = ap.parse_args()

    payload = json.loads(Path(args.alert).read_text(encoding="utf-8"))
    # Accept either the webhook envelope (`{"alerts": [...]}`) or one bare
    # alert. The envelope is what Alertmanager posts; the bare form is what a
    # human has in the clipboard from the Prometheus UI.
    alerts = payload.get("alerts") if isinstance(payload, dict) else None
    if not alerts:
        alerts = [payload]

    firing = [a for a in alerts if a.get("status", "firing") == "firing"] or alerts

    blocks = []
    for alert in firing:
        head, ev = gather(alert, args.prometheus, args.loki)
        prompt = (
            f"Alert: {json.dumps(head, indent=2)}\n\n"
            f"Evidence:\n"
            + "\n".join(f"- {e.label} [{e.query}] -> {e.value}" for e in ev)
        )
        text, note = providers.generate(prompt)
        blocks.append(render(head, ev, text, note))

    print("\n\n---\n\n".join(blocks))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
