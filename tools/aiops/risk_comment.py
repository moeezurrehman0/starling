# SPDX-License-Identifier: MIT
"""Post a plain-English risk summary of a Terraform plan.

Usage:
    python tools/aiops/risk_comment.py \
        --plan build/plan.json \
        [--checkov build/checkov.json] \
        [--infracost build/infracost.json] \
        [--title "Tier P"] [--json build/risk.json]

Writes Markdown to stdout. Exits non-zero only on a malformed plan file: the
commenter's own findings never fail the build. Blocking a merge is the job of
`tf-validate.sh`, `tf-test.sh` and Checkov, which are deterministic, reviewed
and negative-tested. A summary that can block is a summary people learn to
argue with rather than read.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import providers  # noqa: E402
import risk  # noqa: E402

MARKER = "<!-- aiops:terraform-risk -->"

BADGE = {
    "critical": "🔴 **critical**",
    "high": "🟠 high",
    "medium": "🟡 medium",
    "low": "⚪ low",
    "info": "✅ nothing notable",
}

LEAD = {
    "critical": "This plan destroys or unprotects something that holds data.",
    "high": "This plan interrupts traffic or widens network exposure.",
    "medium": "This plan changes permissions, cost, or destroys something replaceable.",
    "low": "Minor findings only.",
    "info": "No findings from the automated checks.",
}


def render_table(report: risk.Report) -> str:
    if not report.findings:
        return "_No findings._\n"
    rows = [
        "| Severity | Kind | Address | What |",
        "|---|---|---|---|",
    ]
    for f in report.sorted_findings():
        # Addresses contain `[` and `|` in for_each keys; the pipe would break
        # the table and the brackets would render as a broken link.
        addr = f.address.replace("|", "\\|")
        what = f.summary if not f.evidence else f"{f.summary}<br><sub>{f.evidence}</sub>"
        rows.append(f"| {BADGE.get(f.severity, f.severity)} | {f.kind} | `{addr}` | {what} |")
    return "\n".join(rows) + "\n"


def render_counts(report: risk.Report) -> str:
    c = report.counts
    parts = [
        f"**{c.get('create', 0)}** to add",
        f"**{c.get('update', 0)}** to change",
        f"**{c.get('replace', 0)}** to replace",
        f"**{c.get('delete', 0)}** to destroy",
    ]
    return ", ".join(parts)


def render_cost(report: risk.Report) -> str:
    if not report.cost:
        return (
            "_No cost estimate: `INFRACOST_API_KEY` is not configured. "
            "This is a **skip, not a zero**._"
        )
    diff = float(report.cost["diff"])  # type: ignore[arg-type]
    total = float(report.cost["total"])  # type: ignore[arg-type]
    cur = report.cost["currency"]
    sign = "+" if diff >= 0 else ""
    return f"Monthly cost **{sign}{diff:.2f} {cur}** → **{total:.2f} {cur}**/month."


def build_prompt(report: risk.Report, title: str) -> str:
    """What the model is given. Findings only — never the plan.

    The plan file contains resource ids, account-shaped strings and occasionally
    values marked sensitive. None of that helps the explanation, and sending it
    would make this tool the single place in the pipeline where infrastructure
    detail leaves the runner.
    """
    return (
        f"Change set: {title}\n"
        f"Plan summary: {render_counts(report)}\n"
        f"Findings (already judged, do not re-rank):\n"
        f"{json.dumps(report.to_dict(), indent=2)}\n"
    )


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--plan", required=True)
    ap.add_argument("--checkov")
    ap.add_argument("--infracost")
    ap.add_argument("--title", default="Terraform plan")
    ap.add_argument("--json", dest="json_out")
    args = ap.parse_args()

    try:
        plan = risk.load(args.plan)
    except json.JSONDecodeError as exc:
        print(f"could not parse {args.plan}: {exc}", file=sys.stderr)
        return 2
    if not plan:
        print(f"no plan at {args.plan}", file=sys.stderr)
        return 2

    report = risk.analyse_plan(plan)
    risk.add_checkov(report, risk.load(args.checkov))
    risk.add_infracost(report, risk.load(args.infracost))

    if args.json_out:
        Path(args.json_out).write_text(
            json.dumps(report.to_dict(), indent=2), encoding="utf-8"
        )

    text, note = providers.generate(build_prompt(report, args.title))

    out = [
        # Stable marker so CI can update one comment in place. Without it every
        # push appends another comment and the PR becomes unreadable by review
        # time -- which is the same "technically correct, practically ignored"
        # failure as the 25 duplicate IAM findings this tool used to emit.
        MARKER,
        f"## Infrastructure risk — {args.title}",
        "",
        f"{BADGE.get(report.worst, report.worst)} — {LEAD.get(report.worst, '')}",
        "",
        render_counts(report) + ".",
        "",
        render_cost(report),
        "",
    ]
    if text:
        out += ["### What this means", "", text, ""]
    out += ["### Findings", "", render_table(report), ""]
    if report.security_creates:
        out += [
            f"<sub>{report.security_creates} security resource(s) are created "
            "with no wildcard action, wildcard principal or open ingress. "
            "Counted, not listed.</sub>",
            "",
        ]
    out += [
        "<sub>Severities are assigned by `tools/aiops/risk.py`, not by a model — "
        f"see `docs/07-aiops.md`. Generation: {note}. "
        "This comment never fails the build; `tf-validate.sh`, `tf-test.sh` and "
        "Checkov do that.</sub>",
    ]
    print("\n".join(out))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
