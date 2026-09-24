// SPDX-License-Identifier: MIT
//
// Steady: constant background traffic for the duration of a canary rollout.
//
// Argo Rollouts' analysis queries Prometheus for the canary's error rate. With
// no traffic that query returns no data, and "no data" is not "healthy" — it is
// the absence of evidence. The rollout would then either stall on
// `inconclusive` or, if configured to treat empty as success, promote a broken
// build on the strength of having received no requests.
//
// This scenario exists to make the analysis answerable. It is low rate and long
// duration: the canary needs *enough* requests to produce a statistically
// meaningful error rate, not a lot of them.

import { sleep } from 'k6';
import { enrol, post, timeline } from './lib/flow.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:18081';
const RATE = Number(__ENV.RATE || 5);
const DURATION = __ENV.DURATION || '10m';

export const options = {
  scenarios: {
    steady: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: Math.max(10, RATE * 2),
      maxVUs: Math.max(30, RATE * 5),
    },
  },
  // No thresholds. This scenario runs *while* a deliberately broken canary is
  // being rejected, so errors are the expected outcome. Failing the k6 run for
  // observing the failure the drill is designed to produce would make the drill
  // unable to pass.
};

const accounts = {};

export default function () {
  let account = accounts[__VU];
  if (!account) {
    account = enrol(BASE_URL, __VU, __ITER);
    if (!account) {
      // Back off rather than hot-looping on a service that is mid-rollout.
      sleep(2);
      return;
    }
    accounts[__VU] = account;
  }

  if (__ITER % 5 === 0) {
    post(BASE_URL, account.token, `steady tweet vu=${__VU}`);
  } else {
    timeline(BASE_URL, account.token);
  }
  sleep(0.2);
}
