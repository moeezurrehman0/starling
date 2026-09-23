#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Create every DynamoDB table declared in tools/dynamodb-tables.json.

Why this exists
---------------
The table definitions have exactly one home (``tools/dynamodb-tables.json``) so that the local
Compose stack and the Terraform data module cannot drift apart. This script is the local half
of that arrangement: it reads the same file Terraform reads and applies it to whatever endpoint
it is pointed at.

That matters because schema drift between local and deployed has a nasty signature. A GSI that
exists locally but not in AWS produces a ``ValidationException`` on the first query that uses
it, which is to say after deploy, on one code path, under load. Reading both from one file
turns that class of bug into a merge conflict.

Where it runs
-------------
Normally inside the LocalStack container, invoked by an init hook in
``/etc/localstack/init/ready.d`` once LocalStack reports ready. It is also runnable from a host
or a CI job against any endpoint::

    AWS_ENDPOINT_URL=http://localhost:4566 python3 tools/localstack/create-tables.py

Idempotence
-----------
Creating a table that already exists is treated as success, not as an error. The init hook runs
on every container start, including restarts against a persisted volume, and a bootstrap that
fails the second time you run it is a bootstrap nobody trusts.

What it deliberately does not do
--------------------------------
It does not update an existing table to match a changed definition. DynamoDB cannot add a key
schema or change a key type in place anyway, and quietly mutating a table under a running
service is worse than refusing. Locally the answer is to recreate the volume; in AWS it is
Terraform's problem, and an expand-contract migration.
"""

from __future__ import annotations

import json
import os
import pathlib
import sys
import time

try:
    import boto3
    from botocore.config import Config
    from botocore.exceptions import ClientError, EndpointConnectionError
except ImportError:  # pragma: no cover - only hit outside the container
    sys.exit(
        "boto3 is required. Inside the LocalStack container it is already present; "
        "on a host, run: python3 -m pip install boto3"
    )

# The definitions file sits one directory up from this script in the repo, and is mounted
# alongside it in the container. Resolve both rather than hard-coding one.
_HERE = pathlib.Path(__file__).resolve().parent
_CANDIDATES = [_HERE.parent / "dynamodb-tables.json", _HERE / "dynamodb-tables.json"]


def _definitions_path() -> pathlib.Path:
    override = os.environ.get("DYNAMODB_TABLES_FILE")
    if override:
        return pathlib.Path(override)
    for candidate in _CANDIDATES:
        if candidate.is_file():
            return candidate
    sys.exit(f"Could not find dynamodb-tables.json; looked in {[str(c) for c in _CANDIDATES]}")


def _strip_comments(value):
    """Drop the ``$comment`` keys used to document the definitions file.

    The definitions are JSON because Terraform's ``jsondecode`` can read it and YAML it cannot.
    JSON has no comments, and these definitions carry a lot of reasoning that would otherwise
    have to live somewhere the next reader will not look. ``$``-prefixed keys are the
    convention; they are stripped here and ignored by Terraform's ``lookup``.
    """
    if isinstance(value, dict):
        return {k: _strip_comments(v) for k, v in value.items() if not k.startswith("$")}
    if isinstance(value, list):
        return [_strip_comments(v) for v in value]
    return value


def _key_schema(spec: dict) -> list[dict]:
    schema = [{"AttributeName": spec["hash_key"], "KeyType": "HASH"}]
    if spec.get("range_key"):
        schema.append({"AttributeName": spec["range_key"], "KeyType": "RANGE"})
    return schema


def _table_request(name: str, spec: dict) -> dict:
    request = {
        "TableName": name,
        "BillingMode": "PAY_PER_REQUEST",
        "KeySchema": _key_schema(spec),
        "AttributeDefinitions": [
            {"AttributeName": attr, "AttributeType": kind}
            for attr, kind in sorted(spec["attributes"].items())
        ],
    }

    indexes = spec.get("global_secondary_indexes") or {}
    if indexes:
        request["GlobalSecondaryIndexes"] = [
            {
                "IndexName": index_name,
                "KeySchema": _key_schema(index),
                "Projection": {"ProjectionType": index["projection"]},
            }
            for index_name, index in sorted(indexes.items())
        ]

    if spec.get("stream"):
        request["StreamSpecification"] = {
            "StreamEnabled": True,
            "StreamViewType": spec["stream"],
        }

    return request


def _client():
    # LocalStack accepts any credentials but botocore refuses to sign without some, so dummies
    # are supplied rather than relying on an ambient profile that may not exist in CI.
    endpoint = os.environ.get("AWS_ENDPOINT_URL", "http://localhost:4566")
    return boto3.client(
        "dynamodb",
        endpoint_url=endpoint,
        region_name=os.environ.get("AWS_REGION", "us-east-1"),
        aws_access_key_id=os.environ.get("AWS_ACCESS_KEY_ID", "test"),
        aws_secret_access_key=os.environ.get("AWS_SECRET_ACCESS_KEY", "test"),
        # Short timeouts with a few retries: the init hook can fire a moment before the
        # DynamoDB provider finishes starting, and failing fast then retrying beats a hook
        # that hangs for two minutes and leaves the stack looking healthy but empty.
        config=Config(connect_timeout=5, read_timeout=15, retries={"max_attempts": 5}),
    )


def _wait_until_available(client, deadline_seconds: int = 60) -> None:
    deadline = time.monotonic() + deadline_seconds
    while True:
        try:
            client.list_tables()
            return
        except (EndpointConnectionError, ClientError) as exc:
            if time.monotonic() >= deadline:
                sys.exit(f"DynamoDB never became reachable: {exc}")
            time.sleep(1)


def main() -> int:
    path = _definitions_path()
    tables = _strip_comments(json.loads(path.read_text()))["tables"]

    client = _client()
    _wait_until_available(client)

    existing = set(client.list_tables()["TableNames"])
    prefix = os.environ.get("DYNAMODB_TABLE_PREFIX", "")

    for logical_name, spec in sorted(tables.items()):
        physical = f"{prefix}{logical_name}"
        if physical in existing:
            print(f"  = {physical} (exists)")
            continue

        client.create_table(**{**_table_request(logical_name, spec), "TableName": physical})
        client.get_waiter("table_exists").wait(TableName=physical)

        detail = []
        if spec.get("stream"):
            detail.append(f"stream={spec['stream']}")
        for index_name in sorted(spec.get("global_secondary_indexes") or {}):
            detail.append(f"gsi={index_name}")

        ttl = spec.get("ttl_attribute")
        if ttl:
            # TTL is a separate call; it cannot be set in CreateTable. Forgetting it is silent:
            # items simply never expire and the table grows without bound.
            client.update_time_to_live(
                TableName=physical,
                TimeToLiveSpecification={"Enabled": True, "AttributeName": ttl},
            )
            detail.append(f"ttl={ttl}")

        suffix = f" [{', '.join(detail)}]" if detail else ""
        print(f"  + {physical}{suffix}")

    print(f"DynamoDB bootstrap complete: {len(tables)} tables from {path.name}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
