#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Container healthcheck for LocalStack: ready *and* correctly bootstrapped.

The obvious healthcheck -- a TCP or HTTP probe on 4566 -- goes healthy as soon as the gateway
is listening, which is well before the init hooks have created any tables. Compose would then
release dependent services to start against an empty DynamoDB, and the failure would surface
as a mystifying ResourceNotFoundException in whichever service happened to query first.

The next-most-obvious fix, grepping ``/_localstack/init`` for ``"READY": true``, is worse than
it looks. That flag reports that the READY *stage* finished, not that the scripts in it
succeeded: a hook that exits non-zero still completes the stage. So the probe would pass, the
stack would look healthy, and the tables would silently not exist -- the same shape of
false-pass as the ``docker exec`` distroless check in Phase 3, where a command that could not
run at all was read as a successful test.

So this asserts both: the stage completed, and every script in it reported SUCCESSFUL. It also
requires at least one script to have run, because an empty list would otherwise satisfy
``all()`` and hand back a pass for a stack with no bootstrap at all.
"""

import json
import sys
import urllib.request

try:
    with urllib.request.urlopen("http://localhost:4566/_localstack/init", timeout=5) as response:
        status = json.load(response)
except OSError as exc:
    print(f"localstack unreachable: {exc}", file=sys.stderr)
    sys.exit(1)

if not status.get("completed", {}).get("READY"):
    print("READY stage has not completed", file=sys.stderr)
    sys.exit(1)

scripts = status.get("scripts", [])
if not scripts:
    print("READY completed but no init scripts ran; is the hook volume mounted?", file=sys.stderr)
    sys.exit(1)

failed = [s for s in scripts if s.get("state") != "SUCCESSFUL"]
if failed:
    for script in failed:
        print(f"init script {script.get('name')}: {script.get('state')}", file=sys.stderr)
    sys.exit(1)
