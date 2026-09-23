#!/bin/bash
# SPDX-License-Identifier: MIT
#
# LocalStack init hook. Everything executable in /etc/localstack/init/ready.d runs once
# LocalStack reports ready, which is what makes `make up` a single command rather than a
# command plus a bootstrap step somebody forgets.
#
# Numbered 10- so that later hooks (S3 buckets, seed data) have an obvious place to slot in
# after the tables exist.
set -euo pipefail

echo "[init] creating DynamoDB tables"

# AWS_ENDPOINT_URL is left at its default of localhost:4566 on purpose: this runs *inside*
# the LocalStack container, so localhost is the right address and pointing it at the compose
# service name would resolve but route back out through the bridge for no reason.
exec python3 /opt/twitter-tools/localstack/create-tables.py
