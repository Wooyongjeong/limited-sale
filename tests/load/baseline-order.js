// 1-11 baseline load: every iteration is one prepared user who creates an order and pays it when created.
//
// The runner (tools/order-regression/run_baseline.py) prepares the users, logs them in before the load and
// passes their sessions through SESSIONS_FILE, so Argon2 login cost never enters these measurements.
//
//   ITERATIONS    total iterations, one per user (default 10000)
//   VUS           concurrent virtual users (default 500)
//   BASE_URL      load balancer address (default http://127.0.0.1:8000)
//   SALE_ID       sale to order from (required)
//   SESSIONS_FILE JSON array of {cookies, csrf, key}; at least ITERATIONS entries (required)
//   SUMMARY_PATH  where handleSummary writes the JSON summary (required)
import http from 'k6/http';
import exec from 'k6/execution';
import { SharedArray } from 'k6/data';
import { Counter, Trend } from 'k6/metrics';

const ITERATIONS = Number(__ENV.ITERATIONS || 10000);
const VUS = Number(__ENV.VUS || 500);
const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:8000';
const SALE_ID = Number(__ENV.SALE_ID);
const SUMMARY_PATH = __ENV.SUMMARY_PATH;

const sessions = new SharedArray('sessions', () => JSON.parse(open(__ENV.SESSIONS_FILE)));

export const options = {
  scenarios: {
    baseline: {
      executor: 'shared-iterations',
      iterations: ITERATIONS,
      vus: VUS,
      maxDuration: __ENV.MAX_DURATION || '10m',
    },
  },
  summaryTrendStats: ['count', 'avg', 'min', 'med', 'max', 'p(50)', 'p(95)', 'p(99)'],
};

// 409 is a business rejection, so only 5xx and transport errors count as http_req_failed.
http.setResponseCallback(http.expectedStatuses(200, 201, 202, 409));

const ENDPOINTS = ['order_create', 'order_payment'];
const durations = {};
for (const endpoint of ENDPOINTS) {
  durations[endpoint] = new Trend(`${endpoint}_duration`, true);
}

const STATUSES = [200, 201, 202, 400, 401, 403, 404, 409, 429, 500, 502, 503, 504];
const statusCounters = {};
for (const status of STATUSES) {
  statusCounters[status] = new Counter(`status_${status}`);
}
const statusOther = new Counter('status_other');

const CODES = [
  'ORDER_CREATED', 'ORDER_FOUND', 'SALE_SOLD_OUT', 'PURCHASE_LIMIT_EXCEEDED', 'SALE_OUTSIDE_PERIOD',
  'SALE_STOPPED', 'SALE_NOT_FOUND', 'IDEMPOTENCY_CONFLICT', 'PAYMENT_SUCCEEDED', 'PAYMENT_PENDING',
  'PAYMENT_DECLINED', 'ORDER_EXPIRED', 'ORDER_NOT_FOUND',
];
const codeCounters = {};
for (const code of CODES) {
  codeCounters[code] = new Counter(`code_${code}`);
}
const codeOther = new Counter('code_other');
const codeNone = new Counter('code_none');

const connectionErrors = new Counter('connection_errors');
const serverErrors = new Counter('server_errors_5xx');

function headers(session, extra) {
  return Object.assign({
    'Content-Type': 'application/json',
    Cookie: Object.entries(session.cookies).map(([name, value]) => `${name}=${value}`).join('; '),
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
  (statusCounters[response.status] || statusOther).add(1);
  if (response.status >= 500) {
    serverErrors.add(1);
  }
  const code = businessCode(response);
  if (code === undefined) {
    codeNone.add(1);
  } else {
    (codeCounters[code] || codeOther).add(1);
  }
  return code;
}

export function setup() {
  if (!SALE_ID || !SUMMARY_PATH) {
    throw new Error('SALE_ID and SUMMARY_PATH are required');
  }
  if (sessions.length < ITERATIONS) {
    throw new Error(`${sessions.length} prepared sessions for ${ITERATIONS} iterations`);
  }
}

export default function () {
  const session = sessions[exec.scenario.iterationInTest];
  // A fresh jar per iteration: the session travels only in the explicit Cookie header.
  const jar = new http.CookieJar();
  const created = http.post(`${BASE_URL}/api/v1/orders`, JSON.stringify({ saleId: SALE_ID, quantity: 1 }), {
    headers: headers(session, { 'Idempotency-Key': session.key }),
    jar,
    tags: { endpoint: 'order_create', name: 'POST /api/v1/orders' },
  });
  if (record('order_create', created) !== 'ORDER_CREATED') {
    return;
  }
  const orderId = created.json('data.orderId');
  const paid = http.post(`${BASE_URL}/api/v1/orders/${orderId}/payment`, null, {
    headers: headers(session),
    jar,
    tags: { endpoint: 'order_payment', name: 'POST /api/v1/orders/{id}/payment' },
  });
  record('order_payment', paid);
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
  const summary = {
    iterations_planned: ITERATIONS,
    vus: VUS,
    duration_seconds: seconds,
    iterations_completed: counterValue(data, 'iterations'),
    dropped_iterations: counterValue(data, 'dropped_iterations'),
    iterations_per_second: counterValue(data, 'iterations') / seconds,
    http_requests: requests,
    http_requests_per_second: requests / seconds,
    http_req_failed_rate: data.metrics.http_req_failed ? data.metrics.http_req_failed.values.rate : 0,
    latency: Object.fromEntries(ENDPOINTS.map((endpoint) => [endpoint, trend(data, `${endpoint}_duration`)])),
    http_req_duration: trend(data, 'http_req_duration'),
    status_counts: nonZero(data, 'status_'),
    business_codes: nonZero(data, 'code_'),
    server_errors_5xx: counterValue(data, 'server_errors_5xx'),
    connection_errors: counterValue(data, 'connection_errors'),
  };
  return {
    [SUMMARY_PATH]: JSON.stringify(summary, null, 2),
    stdout: `k6 ${ITERATIONS} iterations in ${seconds.toFixed(1)}s, ${requests} requests, `
      + `${summary.server_errors_5xx} 5xx, ${summary.connection_errors} connection errors\n`,
  };
}
