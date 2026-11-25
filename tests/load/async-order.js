// 3-8 v2 load: every user accepts one order through POST /api/v2/orders, polls the acceptance until it is final and,
// when it became an order, pays it with the v1 payment API, in one k6 run through the load balancer.
//
// The runner (tools/order-regression/run_comparison.py, condition v2-async, and run_async_faults.py) prepares and
// logs in every user before the load and passes the sessions through SESSIONS_FILE, exactly as for the v1 comparison
// script, so both flows start from the same state and Argon2 login cost never enters the measurements.
//
//   ITERATIONS     users, one iteration each (default 10000)
//   VUS            concurrent virtual users (default 500)
//   BASE_URL       load balancer address (default http://127.0.0.1:8000)
//   SALE_ID        sale to order from (required)
//   SESSIONS_FILE  JSON array of {cookies, csrf, key} with ITERATIONS entries (required)
//   SUMMARY_PATH   where handleSummary writes the JSON summary (required)
//   POLL_DEADLINE  seconds a user keeps polling one acceptance before giving up (default 360)
//
// Polling waits 0.5 s before the first status request and doubles the wait up to 2 s (3-8 #2). A 5xx or a lost
// connection is retried up to 30 times, one second apart, with the same request (the same Idempotency-Key for the
// acceptance): every endpoint used here answers a repeat with the stored result. The fault runs need this, because a
// killed instance behind the load balancer answers 502; every 5xx is still counted in server_errors_5xx, and a request
// that never got an answer is counted in unresolved_requests.
//
// The summary keeps the v1 comparison script's shape (endpoint names, business codes, latency_by_code), with the
// acceptance in place of order_create, so tools/order-regression/comparison.py aggregates both flows the same way.
import http from 'k6/http';
import exec from 'k6/execution';
import { sleep } from 'k6';
import { SharedArray } from 'k6/data';
import { Counter, Trend } from 'k6/metrics';

const ITERATIONS = Number(__ENV.ITERATIONS || 10000);
const VUS = Number(__ENV.VUS || 500);
const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:8000';
const SALE_ID = Number(__ENV.SALE_ID);
const SUMMARY_PATH = __ENV.SUMMARY_PATH;
const POLL_DEADLINE_MS = Number(__ENV.POLL_DEADLINE || 360) * 1000;
const RETRIES = 30;

const sessions = new SharedArray('sessions', () => JSON.parse(open(__ENV.SESSIONS_FILE)));

export const options = {
  scenarios: {
    normal: {
      executor: 'shared-iterations',
      exec: 'normal',
      iterations: ITERATIONS,
      vus: VUS,
      maxDuration: __ENV.MAX_DURATION || '20m',
    },
  },
  summaryTrendStats: ['count', 'avg', 'min', 'med', 'max', 'p(50)', 'p(95)', 'p(99)'],
};

// 409 and 429 are business rejections, so only 5xx and transport errors count as http_req_failed.
http.setResponseCallback(http.expectedStatuses(200, 201, 202, 409, 429));

// order_create is the acceptance here; the abuse and detail endpoints stay empty so the summary has the v1 shape.
const ENDPOINTS = ['order_create', 'order_status', 'order_payment', 'sale_detail', 'abuse_order_create',
  'abuse_order_payment'];
const STATUSES = [200, 201, 202, 400, 401, 403, 404, 409, 429, 500, 502, 503, 504];
const CODES = [
  'ORDER_ACCEPTED', 'ORDER_ACCEPTANCE_FOUND', 'ORDER_ACCEPTANCE_NOT_FOUND', 'SALE_SOLD_OUT',
  'PURCHASE_LIMIT_EXCEEDED', 'SALE_OUTSIDE_PERIOD', 'SALE_STOPPED', 'SALE_NOT_FOUND', 'IDEMPOTENCY_CONFLICT',
  'PAYMENT_SUCCEEDED', 'PAYMENT_PENDING', 'PAYMENT_DECLINED', 'ORDER_EXPIRED', 'ORDER_NOT_FOUND',
  'ORDER_RATE_LIMITED',
];
// Final acceptance states as the user saw them: ORDER_CREATED, a rejection reason, or a failure reason.
const FINALS = ['ORDER_CREATED', 'SALE_SOLD_OUT', 'PURCHASE_LIMIT_EXCEEDED', 'SALE_STOPPED', 'SALE_OUTSIDE_PERIOD',
  'SALE_NOT_FOUND', 'TRANSIENT_RETRIES_EXHAUSTED', 'PROCESSING_ERROR', 'STALLED', 'POLL_DEADLINE'];

const durations = {};
const statusCounters = {};
const codeCounters = {};
const statusOther = {};
for (const endpoint of ENDPOINTS) {
  durations[endpoint] = new Trend(`${endpoint}_duration`, true);
  for (const status of STATUSES) {
    statusCounters[`${endpoint}_${status}`] = new Counter(`${endpoint}_status_${status}`);
  }
  statusOther[endpoint] = new Counter(`${endpoint}_status_other`);
  codeCounters[`${endpoint}_other`] = new Counter(`${endpoint}_code_other`);
  codeCounters[`${endpoint}_none`] = new Counter(`${endpoint}_code_none`);
  for (const code of CODES) {
    codeCounters[`${endpoint}_${code}`] = new Counter(`${endpoint}_code_${code}`);
  }
}
const finalCounters = {};
for (const name of FINALS) {
  finalCounters[name] = new Counter(`final_${name}`);
}
const finalOther = new Counter('final_other');

// Acceptance response latency split by the answer, as the v1 script splits order creation.
const LATENCY_CODES = ['ORDER_ACCEPTED', 'SALE_SOLD_OUT'];
const latencyByCode = {};
for (const code of LATENCY_CODES) {
  latencyByCode[code] = new Trend(`order_create_latency_${code}`, true);
}
// From sending the acceptance until the user saw its final state; resolution is the polling interval.
const acceptToFinal = new Trend('accept_to_final_ms', true);
// From sending the order request until the payment came back succeeded (the same measure as the v1 script's).
const requestToConfirmed = new Trend('request_to_confirmed_ms', true);
const pollsPerUser = new Trend('polls_per_acceptance');

const connectionErrors = new Counter('connection_errors');
const serverErrors = new Counter('server_errors_5xx');
const retriedRequests = new Counter('retried_requests');
const unresolvedRequests = new Counter('unresolved_requests');
// A 2xx whose body has no readable business code: the response was cut short (2-5 finding 1, fixed in 4-2).
const broken2xx = new Counter('broken_2xx');
const normalElapsed = new Trend('normal_elapsed_ms');
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
  if (code === undefined && response.status >= 200 && response.status < 300) {
    broken2xx.add(1);
  }
  if (code === undefined) {
    codeCounters[`${endpoint}_none`].add(1);
  } else {
    (codeCounters[`${endpoint}_${code}`] || codeCounters[`${endpoint}_other`]).add(1);
  }
  return code;
}

/** Sends until the answer is not a 5xx or lost connection; every attempt is recorded. Returns [response, code]. */
function withRetry(endpoint, send) {
  for (let attempt = 0; ; attempt++) {
    const response = send();
    const code = record(endpoint, response);
    if (response.status !== 0 && response.status < 500) {
      return [response, code];
    }
    if (attempt >= RETRIES) {
      unresolvedRequests.add(1);
      return [response, code];
    }
    retriedRequests.add(1);
    sleep(1);
  }
}

function accept(session, jar) {
  return withRetry('order_create', () => http.post(`${BASE_URL}/api/v2/orders`,
    JSON.stringify({ saleId: SALE_ID, quantity: 1 }), {
      headers: headers(session, { 'Idempotency-Key': session.key }),
      jar,
      tags: { endpoint: 'order_create', name: 'POST /api/v2/orders' },
    }));
}

function status(session, jar, acceptanceId) {
  return withRetry('order_status', () => http.get(`${BASE_URL}/api/v2/order-requests/${acceptanceId}`, {
    headers: { Cookie: cookie(session) },
    jar,
    tags: { endpoint: 'order_status', name: 'GET /api/v2/order-requests/{id}' },
  }));
}

function pay(session, jar, orderId) {
  return withRetry('order_payment', () => http.post(`${BASE_URL}/api/v1/orders/${orderId}/payment`, null, {
    headers: headers(session),
    jar,
    tags: { endpoint: 'order_payment', name: 'POST /api/v1/orders/{id}/payment' },
  }));
}

export function setup() {
  if (!SALE_ID || !SUMMARY_PATH) {
    throw new Error('SALE_ID and SUMMARY_PATH are required');
  }
  if (sessions.length < ITERATIONS) {
    throw new Error(`${sessions.length} prepared sessions for ${ITERATIONS} users`);
  }
}

export function normal() {
  const session = sessions[exec.scenario.iterationInTest];
  const jar = new http.CookieJar();
  const started = Date.now();
  const [accepted, code] = accept(session, jar);
  if (latencyByCode[code]) {
    latencyByCode[code].add(accepted.timings.duration);
  }
  // 202 for a new acceptance; 200 when a retry found the acceptance the lost answer had created.
  if (code === 'ORDER_ACCEPTED' || code === 'ORDER_ACCEPTANCE_FOUND') {
    const acceptanceId = accepted.json('data.acceptanceId');
    let wait = 500;
    let polls = 0;
    let final;
    let orderId;
    while (Date.now() - started < POLL_DEADLINE_MS) {
      sleep(wait / 1000);
      wait = Math.min(wait * 2, 2000);
      const [response] = status(session, jar, acceptanceId);
      polls++;
      if (response.status !== 200) {
        continue;
      }
      const data = response.json('data');
      if (data.status !== 'ACCEPTED') {
        final = data.status === 'ORDER_CREATED' ? 'ORDER_CREATED' : (data.rejectionReason || data.failureReason);
        orderId = data.orderId;
        break;
      }
    }
    pollsPerUser.add(polls);
    if (final === undefined) {
      final = 'POLL_DEADLINE';
    } else {
      acceptToFinal.add(Date.now() - started);
    }
    (finalCounters[final] || finalOther).add(1);
    if (final === 'ORDER_CREATED') {
      const [, paid] = pay(session, jar, orderId);
      if (paid === 'PAYMENT_SUCCEEDED') {
        requestToConfirmed.add(Date.now() - started);
      }
    }
  }
  normalElapsed.add(exec.instance.currentTestRunDuration);
  normalIterations.add(1);
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
  const polls = data.metrics.polls_per_acceptance ? data.metrics.polls_per_acceptance.values : {};
  const summary = {
    api: 'v2',
    iterations_planned: ITERATIONS,
    vus: VUS,
    detail_views: 0,
    abusers: 0,
    abuser_vus_per_member: 1,
    duration_seconds: seconds,
    normal_duration_seconds: normalEnd === undefined ? undefined : normalEnd / 1000,
    iterations_completed: counterValue(data, 'normal_iterations'),
    dropped_iterations: counterValue(data, 'dropped_iterations'),
    http_requests: requests,
    http_requests_per_second: requests / seconds,
    http_req_failed_rate: data.metrics.http_req_failed ? data.metrics.http_req_failed.values.rate : 0,
    endpoints,
    latency_by_code: Object.fromEntries(LATENCY_CODES.map((code) => [code, trend(data, `order_create_latency_${code}`)])),
    accept_to_final: trend(data, 'accept_to_final_ms'),
    request_to_confirmed: trend(data, 'request_to_confirmed_ms'),
    polls_per_acceptance: { avg: polls.avg, max: polls.max, p50: polls['p(50)'], p95: polls['p(95)'] },
    finals: nonZero(data, 'final_'),
    server_errors_5xx: counterValue(data, 'server_errors_5xx'),
    connection_errors: counterValue(data, 'connection_errors'),
    retried_requests: counterValue(data, 'retried_requests'),
    unresolved_requests: counterValue(data, 'unresolved_requests'),
    broken_2xx: counterValue(data, 'broken_2xx'),
  };
  return {
    [SUMMARY_PATH]: JSON.stringify(summary, null, 2),
    stdout: `k6 v2 ${ITERATIONS} users in ${seconds.toFixed(1)}s, ${requests} requests, `
      + `${summary.server_errors_5xx} 5xx, ${summary.unresolved_requests} unresolved\n`,
  };
}
