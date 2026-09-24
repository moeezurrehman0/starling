// SPDX-License-Identifier: MIT
//
// Smoke: one VU, one pass through the product flow.
//
// This runs in CI on every pull request. It is not a load test — it is the
// assertion that the load test itself still works. k6 scripts rot silently:
// a renamed route turns into a 404, every check fails, and the failure reads
// like a performance regression. Running the same script at one VU makes that
// break loudly and cheaply, in seconds, before anyone interprets a p95.

import { sleep } from 'k6';
import { enrol, post, timeline } from './lib/flow.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:18081';

export const options = {
  vus: 1,
  iterations: 1,
  // Absolute, not statistical. At one iteration a percentile is meaningless;
  // what matters is that every single call succeeded.
  thresholds: {
    flow_failures: ['rate==0'],
    checks: ['rate==1'],
  },
};

export default function () {
  const account = enrol(BASE_URL, __VU, __ITER);
  if (!account) {
    return;
  }
  post(BASE_URL, account.token, 'smoke test tweet');
  sleep(1);
  timeline(BASE_URL, account.token);
}
