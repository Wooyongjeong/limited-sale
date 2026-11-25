// 2-6 comparison load: the 1-11 baseline flow with optional sale detail reads before each order and optional
// abusive users who hammer order creation, in one k6 run through the load balancer.
//
// The runner (tools/order-regression/run_comparison.py) prepares and logs in every user before the load and passes
// the sessions through SESSIONS_FILE: the first ITERATIONS entries are the normal users, the next ABUSERS entries
// the abusive ones. Argon2 login cost never enters these measurements.
//
//   ITERATIONS    normal users, one iteration each (default 10000)
//   VUS           concurrent virtual users of the normal scenario (default 500)
//   DETAIL_VIEWS  GET /api/v1/sales/{id} requests, with the session cookie, before each normal order (default 0)
//   ABUSERS       abusive users; 0 leaves them out (default 0)
//   ABUSER_VUS    concurrent VUs per abusive user, all sharing that user's session (default 1)
//   BASE_URL      load balancer address (default http://127.0.0.1:8000)
//   SALE_ID       sale to order from (required)
//   SESSIONS_FILE JSON array of {cookies, csrf, key} with ITERATIONS + ABUSERS entries (required)
//   SUMMARY_PATH  where handleSummary writes the JSON summary (required)
//
// Each abusive VU loops without waiting: every POST /api/v1/orders carries a new Idempotency-Key, a 429 is
// followed at once by the next request and Retry-After is ignored. When an order is created it is paid once, as
// a real user would, so every created order can reach a final state. The loop ends when the normal scenario has
// completed all its iterations: abusive VUs finish a single iteration only at that point, so the instance's
// completed-iteration count is the normal scenario's alone until then.
import http from 'k6/http';
import exec from 'k6/execution';
import { SharedArray } from 'k6/data';
import { Counter, Trend } from 'k6/metrics';

const ITERATIONS = Number(__ENV.ITERATIONS || 10000);
const VUS = Number(__ENV.VUS || 500);
const DETAIL_VIEWS = Number(__ENV.DETAIL_VIEWS || 0);
const ABUSERS = Number(__ENV.ABUSERS || 0);
const ABUSER_VUS = Number(__ENV.ABUSER_VUS || 1);
const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:8000';
const SALE_ID = Number(__ENV.SALE_ID);
const SUMMARY_PATH = __ENV.SUMMARY_PATH;

const sessions = new SharedArray('sessions', () => JSON.parse(open(__ENV.SESSIONS_FILE)));

const scenarios = {
  normal: {
    executor: 'shared-iterations',
    exec: 'normal',
    iterations: ITERATIONS,
    vus: VUS,
    maxDuration: __ENV.MAX_DURATION || '10m',
  },
};
if (ABUSERS > 0) {
  scenarios.abuse = {
    executor: 'shared-iterations',
    exec: 'abuse',
    iterations: ABUSERS * ABUSER_VUS,
    vus: ABUSERS * ABUSER_VUS,
    maxDuration: __ENV.MAX_DURATION || '10m',
  };
}

export const options = {
  scenarios,
  summaryTrendStats: ['count', 'avg', 'min', 'med', 'max', 'p(50)', 'p(95)', 'p(99)'],
};

// 409 and 429 are business rejections, so only 5xx and transport errors count as http_req_failed.
http.setResponseCallback(http.expectedStatuses(200, 201, 202, 409, 429));

const ENDPOINTS = ['order_create', 'order_payment', 'sale_detail', 'abuse_order_create', 'abuse_order_payment'];
const STATUSES = [200, 201, 202, 400, 401, 403, 404, 409, 429, 500, 502, 503, 504];
const CODES = [
  'ORDER_CREATED', 'ORDER_FOUND', 'SALE_SOLD_OUT', 'PURCHASE_LIMIT_EXCEEDED', 'SALE_OUTSIDE_PERIOD',
  'SALE_STOPPED', 'SALE_NOT_FOUND', 'IDEMPOTENCY_CONFLICT', 'PAYMENT_SUCCEEDED', 'PAYMENT_PENDING',
  'PAYMENT_DECLINED', 'ORDER_EXPIRED', 'ORDER_NOT_FOUND', 'ORDER_RATE_LIMITED', 'SALE_FOUND',
];

const durations = {};
const statusCounters = {};
const codeCounters = {};
for (const endpoint of ENDPOINTS) {
  durations[endpoint] = new Trend(`${endpoint}_duration`, true);
  for (const status of STATUSES) {
    statusCounters[`${endpoint}_${status}`] = new Counter(`${endpoint}_status_${status}`);
  }
  codeCounters[`${endpoint}_other`] = new Counter(`${endpoint}_code_other`);
  codeCounters[`${endpoint}_none`] = new Counter(`${endpoint}_code_none`);
  for (const code of CODES) {
    codeCounters[`${endpoint}_${code}`] = new Counter(`${endpoint}_code_${code}`);
  }
}
const statusOther = {};
for (const endpoint of ENDPOINTS) {
  statusOther[endpoint] = new Counter(`${endpoint}_status_other`);
}

// Normal order creation latency split by the answer, so a fast SALE_SOLD_OUT rejection is not averaged into the
// latency of the orders that were actually created.
const LATENCY_CODES = ['ORDER_CREATED', 'SALE_SOLD_OUT'];
const latencyByCode = {};
for (const code of LATENCY_CODES) {
  latencyByCode[code] = new Trend(`order_create_latency_${code}`, true);
}

// From sending the order request until the payment came back succeeded; the v2 script measures the same (3-8).
const requestToConfirmed = new Trend('request_to_confirmed_ms', true);

const connectionErrors = new Counter('connection_errors');
const serverErrors = new Counter('server_errors_5xx');
// When each normal iteration ended, measured from the start of the test; its maximum is the normal scenario's end.
const normalElapsed = new Trend('normal_elapsed_ms');
// The built-in iteration counter would also count the abusive VUs' single long iteration.
const normalIterations = new Counter('normal_iterations');

function cookie(session) {
  return Object.entries(session.cookies).map(([name, value]) => `${name}=${value}`).join('; ');
}

function headers(session, extra) {
  return Object.assign({
    'Content-Type': 'application/json',
    Cookie: cookie(session),
    'X-CSRF-TOKEN': session.csrf,
  }, extra);
}

function businessCode(response) {
  try {
    return response.json('code');
  } catch (error) {
    return undefined;
  }
}

function record(endpoint, response) {
  durations[endpoint].add(response.timings.duration);
  if (response.status === 0) {
    connectionErrors.add(1);
    return undefined;
  }
  (statusCounters[`${endpoint}_${response.status}`] || statusOther[endpoint]).add(1);
  if (response.status >= 500) {
    serverErrors.add(1);
  }
  const code = businessCode(response);
  if (code === undefined) {
    codeCounters[`${endpoint}_none`].add(1);
  } else {
    (codeCounters[`${endpoint}_${code}`] || codeCounters[`${endpoint}_other`]).add(1);
  }
  return code;
}

function createOrder(session, jar, key, endpoint) {
  return http.post(`${BASE_URL}/api/v1/orders`, JSON.stringify({ saleId: SALE_ID, quantity: 1 }), {
    headers: headers(session, { 'Idempotency-Key': key }),
    jar,
    tags: { endpoint, name: 'POST /api/v1/orders' },
  });
}

function payOrder(session, jar, orderId, endpoint) {
  return http.post(`${BASE_URL}/api/v1/orders/${orderId}/payment`, null, {
    headers: headers(session),
    jar,
    tags: { endpoint, name: 'POST /api/v1/orders/{id}/payment' },
  });
}

export function setup() {
  if (!SALE_ID || !SUMMARY_PATH) {
    throw new Error('SALE_ID and SUMMARY_PATH are required');
  }
  if (sessions.length < ITERATIONS + ABUSERS) {
    throw new Error(`${sessions.length} prepared sessions for ${ITERATIONS} users and ${ABUSERS} abusers`);
  }
}

export function normal() {
  const session = sessions[exec.scenario.iterationInTest];
  // A fresh jar per iteration: the session travels only in the explicit Cookie header.
  const jar = new http.CookieJar();
  for (let view = 0; view < DETAIL_VIEWS; view++) {
    // A browser sends the session cookie on every request; the public detail endpoint does not require it.
    const detail = http.get(`${BASE_URL}/api/v1/sales/${SALE_ID}`, {
      headers: { Cookie: cookie(session) },
      jar,
      tags: { endpoint: 'sale_detail', name: 'GET /api/v1/sales/{id}' },
    });
    record('sale_detail', detail);
  }
  const started = Date.now();
  const created = createOrder(session, jar, session.key, 'order_create');
  const code = record('order_create', created);
  if (latencyByCode[code]) {
    latencyByCode[code].add(created.timings.duration);
  }
  if (code === 'ORDER_CREATED') {
    const paid = record('order_payment', payOrder(session, jar, created.json('data.orderId'), 'order_payment'));
    if (paid === 'PAYMENT_SUCCEEDED') {
      requestToConfirmed.add(Date.now() - started);
    }
  }
  normalElapsed.add(exec.instance.currentTestRunDuration);
  normalIterations.add(1);
}

export function abuse() {
  // Consecutive iteration numbers go to the same member, so a member's VUs share one session (cookie and CSRF).
  const session = sessions[ITERATIONS + Math.floor(exec.scenario.iterationInTest / ABUSER_VUS)];
  const jar = new http.CookieJar();
  while (exec.instance.iterationsCompleted < ITERATIONS) {
    const created = createOrder(session, jar, crypto.randomUUID(), 'abuse_order_create');
    if (record('abuse_order_create', created) === 'ORDER_CREATED') {
      record('abuse_order_payment', payOrder(session, jar, created.json('data.orderId'), 'abuse_order_payment'));
    }
  }
}

function counterValue(data, name) {
  const metric = data.metrics[name];
  return metric ? metric.values.count : 0;
}

function trend(data, name) {
  const values = data.metrics[name] ? data.metrics[name].values : {};
  return {
    count: counterValue(data, name),
    avg_ms: values.avg,
    min_ms: values.min,
    p50_ms: values['p(50)'],
    p95_ms: values['p(95)'],
    p99_ms: values['p(99)'],
    max_ms: values.max,
  };
}

function nonZero(data, prefix) {
  const counts = {};
  for (const name of Object.keys(data.metrics)) {
    if (name.startsWith(prefix) && data.metrics[name].values.count > 0) {
      counts[name.slice(prefix.length)] = data.metrics[name].values.count;
    }
  }
  return counts;
}

export function handleSummary(data) {
  const seconds = data.state.testRunDurationMs / 1000;
  const requests = counterValue(data, 'http_reqs');
  const normalEnd = data.metrics.normal_elapsed_ms ? data.metrics.normal_elapsed_ms.values.max : undefined;
  const endpoints = {};
  for (const endpoint of ENDPOINTS) {
    endpoints[endpoint] = Object.assign(trend(data, `${endpoint}_duration`), {
      status_counts: nonZero(data, `${endpoint}_status_`),
      business_codes: nonZero(data, `${endpoint}_code_`),
    });
  }
  const summary = {
    iterations_planned: ITERATIONS,
    vus: VUS,
    detail_views: DETAIL_VIEWS,
    abusers: ABUSERS,
    abuser_vus_per_member: ABUSER_VUS,
    duration_seconds: seconds,
    normal_duration_seconds: normalEnd === undefined ? undefined : normalEnd / 1000,
    iterations_completed: counterValue(data, 'normal_iterations'),
    dropped_iterations: counterValue(data, 'dropped_iterations'),
    http_requests: requests,
    http_requests_per_second: requests / seconds,
    http_req_failed_rate: data.metrics.http_req_failed ? data.metrics.http_req_failed.values.rate : 0,
    endpoints,
    latency_by_code: Object.fromEntries(LATENCY_CODES.map((code) => [code, trend(data, `order_create_latency_${code}`)])),
    request_to_confirmed: trend(data, 'request_to_confirmed_ms'),
    server_errors_5xx: counterValue(data, 'server_errors_5xx'),
    connection_errors: counterValue(data, 'connection_errors'),
  };
  return {
    [SUMMARY_PATH]: JSON.stringify(summary, null, 2),
    stdout: `k6 ${ITERATIONS} users, ${ABUSERS} abusers in ${seconds.toFixed(1)}s, ${requests} requests, `
      + `${summary.server_errors_5xx} 5xx, ${summary.connection_errors} connection errors\n`,
  };
}
