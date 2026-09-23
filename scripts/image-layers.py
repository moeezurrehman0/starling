#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
# ---------------------------------------------------------------------------
# Per-layer size arithmetic for scripts/image-report.sh — ADR-0010.
#
#   image-layers.py <image.tar> [baseline.tar]
#
# Emits shell-sourceable KEY=VALUE lines plus a human-readable layer table.
#
# Why this exists rather than parsing `docker history`:
#
#   `docker history` prints one row per *instruction*, including the many that
#   produce no layer at all (ENV, USER, ENTRYPOINT, WORKDIR). It prints no
#   digest, so there is no key to join it against RootFS.Layers. Any attempt to
#   align the two by position is guesswork, and the guess is wrong: the first N
#   history rows are the newest instructions, which for this image are all
#   zero-byte metadata. An earlier version of the report did exactly that and
#   confidently reported "2 changed layers, 0.0 MB".
#
# `docker save` gives us the join key for free. In the OCI layout Docker now
# writes, each entry of manifest.json's `Layers` is a path to an uncompressed
# layer tar whose filename is its own content digest — which is precisely the
# diff_id that appears in RootFS.Layers. So layer identity, layer order and
# layer size all come from one self-consistent source.
#
# Sizes are reported both raw and gzipped. The gzipped figure is the honest
# one for pull cost: a registry stores and a kubelet fetches each blob
# compressed and independently, which is what we reproduce here. Compressing
# the whole `docker save` stream in one pass — the obvious shortcut — is not
# equivalent, because gzip's window then spans blob boundaries and finds
# cross-layer redundancy that no registry will ever exploit.
# ---------------------------------------------------------------------------
from __future__ import annotations

import gzip
import json
import os
import sys
import tarfile
import tempfile


def read_layers(tar_path: str) -> list[tuple[str, int, int]]:
    """Return [(diff_id, raw_bytes, gzipped_bytes)] ordered base-first."""
    out: list[tuple[str, int, int]] = []
    with tarfile.open(tar_path) as tf:
        manifest_member = tf.extractfile("manifest.json")
        if manifest_member is None:
            raise SystemExit(f"{tar_path}: no manifest.json")
        manifest = json.load(manifest_member)[0]

        for entry in manifest["Layers"]:
            member = tf.getmember(entry)
            diff_id = "sha256:" + os.path.basename(entry)
            stream = tf.extractfile(member)
            if stream is None:
                raise SystemExit(f"{tar_path}: cannot read layer {entry}")

            # Compress in fixed-size chunks; layers reach ~100 MB and holding
            # both the raw and compressed copies in memory is needless.
            compressed = 0
            with tempfile.TemporaryFile() as sink:
                with gzip.GzipFile(fileobj=sink, mode="wb", compresslevel=6, mtime=0) as gz:
                    while chunk := stream.read(1 << 20):
                        gz.write(chunk)
                compressed = sink.tell()

            out.append((diff_id, member.size, compressed))
    return out


def label_for(diff_id: str, config_history: list[str], index: int) -> str:
    return config_history[index] if index < len(config_history) else ""


def layer_labels(tar_path: str) -> list[str]:
    """Instruction text per layer, base-first, skipping empty-layer entries."""
    with tarfile.open(tar_path) as tf:
        manifest = json.load(tf.extractfile("manifest.json"))[0]
        config = json.load(tf.extractfile(manifest["Config"]))
    labels = []
    for step in config.get("history", []):
        if step.get("empty_layer"):
            continue
        text = (step.get("created_by") or "").replace("# buildkit", "").strip()
        labels.append(text[:64])
    return labels


def mb(n: int) -> str:
    return f"{n / 1048576:.1f}"


def main() -> int:
    image_tar = sys.argv[1]
    baseline_tar = sys.argv[2] if len(sys.argv) > 2 else None

    layers = read_layers(image_tar)
    labels = layer_labels(image_tar)

    total_raw = sum(r for _, r, _ in layers)
    total_gz = sum(g for _, _, g in layers)

    print(f"LAYER_COUNT={len(layers)}")
    print(f"TOTAL_RAW={total_raw}")
    print(f"TOTAL_GZ={total_gz}")

    ranked = sorted(
        ((g, r, labels[i] if i < len(labels) else "") for i, (_, r, g) in enumerate(layers)),
        reverse=True,
    )
    # A quoted heredoc assignment, so the caller can `source` this file and
    # get multi-line values without re-parsing or re-quoting them.
    print("TABLE=\"$(cat <<'__TBL__'")
    for gz_size, raw, text in ranked[:8]:
        print(f"    {mb(raw):>8} MB  {mb(gz_size):>8} MB   {text}")
    print("__TBL__")
    print(')"')

    if baseline_tar:
        base_ids = {d for d, _, _ in read_layers(baseline_tar)}
        changed = [(d, r, g) for d, r, g in layers if d not in base_ids]
        print(f"CHANGED_COUNT={len(changed)}")
        print(f"CHANGED_RAW={sum(r for _, r, _ in changed)}")
        print(f"CHANGED_GZ={sum(g for _, _, g in changed)}")
        print("CHANGED_TABLE=\"$(cat <<'__TBL__'")
        order = [d for d, _, _ in layers]
        for diff_id, raw, gz_size in changed:
            idx = order.index(diff_id)
            text = labels[idx] if idx < len(labels) else ""
            print(f"    {mb(raw):>8} MB  {mb(gz_size):>8} MB   {text}")
        print("__TBL__")
        print(')"')

    return 0


if __name__ == "__main__":
    sys.exit(main())
