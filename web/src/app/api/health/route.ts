/* SPDX-License-Identifier: MIT */
import { NextResponse } from "next/server";

/**
 * Liveness and readiness for Kubernetes.
 *
 * Deliberately shallow: it reports that this process can serve a request, and says nothing
 * about the gateway. A readiness probe that checked the API would take every frontend pod out
 * of service during a backend blip, replacing a degraded page with no page at all — and would
 * make a rolling restart of the gateway cascade into the frontend.
 */
export const dynamic = "force-dynamic";

export function GET() {
  return NextResponse.json({ status: "UP" });
}
