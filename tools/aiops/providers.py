# SPDX-License-Identifier: MIT
"""Pluggable text generation, with `none` as the default and the fallback.

Three properties matter more than which model is used:

1. **No provider is a supported configuration, not a broken one.** The default
   is `none`, which renders the findings from a template. Every fact in the
   comment comes from `risk.py` either way, so the unconfigured output is not a
   degraded version of the real output -- it is the same content, tersely
   phrased.

2. **A provider that errors falls back rather than fails.** A rate limit, an
   expired token or a regional outage must not turn a review aid into a red
   check. The fallback is announced in the comment so nobody mistakes the short
   version for the model's considered opinion.

3. **The model never sees credentials, state, or the plan file.** It is given
   the already-computed findings as JSON. There is nothing in that payload that
   is not already visible to anyone who can read the pull request.
"""

from __future__ import annotations

import json
import os
import urllib.error
import urllib.request

TIMEOUT = 45

SYSTEM = """You are reviewing an infrastructure change for an engineer who is \
competent but has not read this Terraform before.

You are given findings that have ALREADY been determined by a deterministic \
analyser. Your job is to explain them, not to re-judge them.

Rules:
- Never contradict a finding's severity, and never add a finding of your own.
- Never say a change is safe. You cannot see the plan, only the findings.
- If there are no findings, say only that the automated checks found nothing \
notable and that this is not a substitute for review.
- Lead with what breaks if this is wrong, then what to check before approving.
- No preamble, no restating the question, no closing pleasantries.
- At most 180 words. Markdown, no headings above level 3."""


class ProviderError(RuntimeError):
    pass


def _post(url: str, payload: dict, headers: dict) -> dict:
    body = json.dumps(payload).encode()
    req = urllib.request.Request(url, data=body, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
            return json.loads(resp.read().decode())
    except urllib.error.HTTPError as exc:  # noqa: PERF203 - message needs the body
        detail = exc.read().decode("utf-8", "replace")[:400]
        raise ProviderError(f"{exc.code} {exc.reason}: {detail}") from exc
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as exc:
        raise ProviderError(str(exc)) from exc


def _openai_compatible(prompt: str, base: str, key: str, model: str) -> str:
    """OpenAI's chat-completions shape.

    Covers GitHub Models, OpenAI and the many gateways that mimic it, which is
    what makes `AIOPS_BASE_URL` worth having: swapping provider is a variable
    change rather than a code change, and the CI job never learns which one is
    in use.
    """
    data = _post(
        f"{base.rstrip('/')}/chat/completions",
        {
            "model": model,
            "temperature": 0.2,
            "messages": [
                {"role": "system", "content": SYSTEM},
                {"role": "user", "content": prompt},
            ],
        },
        {
            "Authorization": f"Bearer {key}",
            "Content-Type": "application/json",
        },
    )
    try:
        return data["choices"][0]["message"]["content"].strip()
    except (KeyError, IndexError, AttributeError) as exc:
        raise ProviderError(f"unexpected response shape: {str(data)[:200]}") from exc


def _bedrock(prompt: str, model: str, region: str) -> str:
    """Bedrock via boto3, imported lazily.

    Lazily because the import is the point of failure: CI has no boto3 and no
    AWS credentials, and a module-level import would make the whole commenter
    unusable in the one environment where it always runs. Tier P swaps
    `AIOPS_PROVIDER` and nothing else.
    """
    try:
        import boto3  # noqa: PLC0415 - see docstring
    except ImportError as exc:
        raise ProviderError("boto3 is not installed") from exc

    client = boto3.client("bedrock-runtime", region_name=region)
    try:
        resp = client.converse(
            modelId=model,
            system=[{"text": SYSTEM}],
            messages=[{"role": "user", "content": [{"text": prompt}]}],
            inferenceConfig={"temperature": 0.2, "maxTokens": 700},
        )
        return resp["output"]["message"]["content"][0]["text"].strip()
    except Exception as exc:  # noqa: BLE001 - botocore raises a wide family
        raise ProviderError(f"bedrock: {exc}") from exc


def generate(prompt: str) -> tuple[str | None, str]:
    """Return `(text, note)`.

    `text` is None when no model produced anything, in which case `note`
    explains why and is printed in the comment. A caller that ignores the note
    and renders only the text produces a comment that looks model-written when
    it is not -- which is the same class of error as a green check that never
    ran.
    """
    provider = (os.environ.get("AIOPS_PROVIDER") or "none").strip().lower()
    if provider in {"", "none", "off"}:
        return None, "no model configured — findings rendered directly"

    model = os.environ.get("AIOPS_MODEL") or ""
    try:
        if provider == "bedrock":
            if not model:
                raise ProviderError("AIOPS_MODEL is required for bedrock")
            region = os.environ.get("AWS_REGION") or "us-east-1"
            return _bedrock(prompt, model, region), f"bedrock:{model}"

        if provider in {"openai", "github", "github-models", "compatible"}:
            key = os.environ.get("AIOPS_API_KEY") or ""
            if not key:
                raise ProviderError("AIOPS_API_KEY is not set")
            base = os.environ.get("AIOPS_BASE_URL") or "https://models.inference.ai.azure.com"
            return _openai_compatible(prompt, base, key, model or "gpt-4o-mini"), (
                f"{provider}:{model or 'gpt-4o-mini'}"
            )

        raise ProviderError(f"unknown AIOPS_PROVIDER {provider!r}")
    except ProviderError as exc:
        # Deliberately not re-raised. See the module docstring: a review aid
        # that can fail the build is a review aid that gets switched off.
        return None, f"model unavailable ({exc}) — findings rendered directly"
