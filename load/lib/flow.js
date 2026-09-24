// SPDX-License-Identifier: MIT
//
// The product flow, shared by every scenario.
//
// This is deliberately the *whole* path — register, log in, post, read a
// timeline — rather than a single cheap endpoint. A load test that hammers
// `/actuator/health` proves the ingress works and nothing else: it never
// touches DynamoDB, never writes to a stream, and never wakes the fan-out
// worker, so it cannot size the system or falsify an SLO.
//
// Each VU owns its own accounts. Sharing one account across VUs would serialise
// every write onto a single partition key and measure DynamoDB's hot-partition
// behaviour instead of the application's.

import http from 'k6/http';
import { check } from 'k6';
import { Trend, Rate } from 'k6/metrics';

// Per-operation latency, because an aggregate p95 across a cheap read and an
// expensive write is a number that describes neither.
export const registerDuration = new Trend('flow_register_duration', true);
export const loginDuration = new Trend('flow_login_duration', true);
export const postDuration = new Trend('flow_post_duration', true);
export const timelineDuration = new Trend('flow_timeline_duration', true);
export const flowFailures = new Rate('flow_failures');

const PASSWORD = 'LoadTest!Password1';

function record(trend, response, expected, name) {
  trend.add(response.timings.duration);
  const ok = check(response, {
    [`${name} -> ${expected}`]: (r) => r.status === expected,
  });
  flowFailures.add(!ok);
  return ok;
}

/**
 * Registers a fresh account and returns its bearer token, or null on failure.
 *
 * The handle embeds the VU and iteration so a rerun against a cluster that was
 * never torn down does not collide on the uniqueness constraint and report a
 * 409 storm as an application regression.
 */
export function enrol(baseUrl, vu, iteration) {
  const handle = `lt${vu}x${iteration}x${Math.floor(Math.random() * 1e6)}`;
  const headers = { 'Content-Type': 'application/json' };

  const created = http.post(
    `${baseUrl}/v1/users`,
    JSON.stringify({
      handle,
      displayName: `Load ${vu}`,
      email: `${handle}@loadtest.invalid`,
      password: PASSWORD,
    }),
    { headers, tags: { op: 'register' } },
  );
  if (!record(registerDuration, created, 201, 'register')) {
    return null;
  }

  const session = http.post(
    `${baseUrl}/v1/sessions`,
    JSON.stringify({ handle, password: PASSWORD }),
    { headers, tags: { op: 'login' } },
  );
  if (!record(loginDuration, session, 200, 'login')) {
    return null;
  }

  const token = session.json('accessToken');
  return token ? { token, handle } : null;
}

/** Posts one tweet as the given account. */
export function post(baseUrl, token, text) {
  const response = http.post(`${baseUrl}/v1/tweets`, JSON.stringify({ text }), {
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    tags: { op: 'post' },
  });
  return record(postDuration, response, 201, 'post');
}

/**
 * Reads the home timeline.
 *
 * Read-your-writes is NOT asserted. Fan-out is asynchronous by design, so a
 * timeline that does not yet contain the tweet posted milliseconds earlier is
 * correct behaviour. Asserting on it here would turn a deliberate architectural
 * property into a flaky load-test failure.
 */
export function timeline(baseUrl, token) {
  const response = http.get(`${baseUrl}/v1/timelines/home`, {
    headers: { Authorization: `Bearer ${token}` },
    tags: { op: 'timeline' },
  });
  return record(timelineDuration, response, 200, 'timeline');
}
