// SPDX-License-Identifier: MIT
//
// Ramp: the test that sizes the system and drives the HPA.
//
// Uses `ramping-arrival-rate`, not `ramping-vus`. The difference decides
// whether the numbers mean anything:
//
//   A closed model (`ramping-vus`) holds concurrency fixed. Each VU waits for
//   its response before issuing the next request, so when the system slows
//   down the offered load falls with it. The system throttles its own
//   workload, latency looks bounded, and the test reports that everything is
//   fine right up to the moment it collapses. This is coordinated omission.
//
//   An open model (`ramping-arrival-rate`) holds *throughput* fixed. Requests
//   arrive on a schedule regardless of whether the previous one finished, which
//   is how real traffic behaves. When the system slows, the queue grows and the
//   measurement shows it.
//
// The read:write mix is 4:1. Twitter-shaped products are overwhelmingly
// read-dominated; a 1:1 mix would size the write path for traffic that does not
// exist and hide the read path behind it.

import { sleep } from 'k6';
import { enrol, post, timeline } from './lib/flow.js';
import { thresholds } from './lib/slo.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:18081';

// Tier L defaults. The kind cluster is three containers inside an 8 GB Docker
// VM already running six JVMs, so the ceiling here is the laptop, not the
// application — see gap row 33. Tier S overrides these via environment.
const PEAK_RPS = Number(__ENV.PEAK_RPS || 20);
const STAGE = __ENV.STAGE_DURATION || '2m';

export const options = {
  scenarios: {
    ramp: {
      executor: 'ramping-arrival-rate',
      startRate: 1,
      timeUnit: '1s',
      // Pre-allocation matters: k6 spinning up VUs mid-test is itself work, and
      // that cost lands in the latency of the requests around it.
      preAllocatedVUs: Math.max(20, PEAK_RPS * 2),
      maxVUs: Math.max(60, PEAK_RPS * 6),
      stages: [
        { target: Math.ceil(PEAK_RPS / 4), duration: '30s' }, // warm the JIT
        { target: Math.ceil(PEAK_RPS / 2), duration: STAGE },
        { target: PEAK_RPS, duration: STAGE }, // HPA should scale out here
        { target: PEAK_RPS, duration: STAGE }, // hold, so the SLO is measured at steady state
        { target: 0, duration: '30s' },
      ],
    },
  },
  thresholds,
  // The first stage is deliberately a warm-up: a cold JVM's p95 is a property
  // of the JIT, not of the architecture, and letting it into the aggregate
  // makes every run look worse than the system it is measuring.
  discardResponseBodies: false,
};

// One account is created per VU on first use and reused afterwards. Creating an
// account per iteration would make bcrypt the dominant cost and turn this into
// a password-hashing benchmark.
const accounts = {};

export default function () {
  let account = accounts[__VU];
  if (!account) {
    account = enrol(BASE_URL, __VU, __ITER);
    if (!account) {
      return;
    }
    accounts[__VU] = account;
  }

  // 4:1 read:write.
  if (__ITER % 5 === 0) {
    post(BASE_URL, account.token, `ramp tweet vu=${__VU} iter=${__ITER}`);
  } else {
    timeline(BASE_URL, account.token);
  }

  // A small think time. Zero think time is not "more realistic load" — it
  // concentrates every VU's requests into a tight loop and measures the
  // client's ability to saturate a socket.
  sleep(0.3);
}
