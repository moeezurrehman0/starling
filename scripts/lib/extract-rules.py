#!/usr/bin/env python3
"""Pull the Prometheus rules out of a rendered chart so promtool can check them.

promtool takes a rules file, not a ConfigMap. Writing the rules twice -- once for
Prometheus and once for the checker -- would let the two drift, and the drift would be
discovered when a rule that CI validated turns out not to be the rule that is running.
"""

from __future__ import annotations

import sys

import yaml


def main(path: str) -> int:
    for doc in yaml.safe_load_all(open(path, encoding="utf-8")):
        if not doc or doc.get("kind") != "ConfigMap":
            continue
        for key, body in (doc.get("data") or {}).items():
            if key.endswith((".yaml", ".yml")) and "groups:" in body:
                sys.stdout.write(body)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
