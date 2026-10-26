import http from 'k6/http';
import crypto from 'k6/crypto';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const SECRET = __ENV.DEMO_WEBHOOK_SECRET || 'dev-secret';
const RATE = Number(__ENV.RATE || 50);
const DURATION = __ENV.DURATION || '30s';

const accepted = new Counter('hookledger_accepted');
const duplicates = new Counter('hookledger_duplicates');
const ingestLatency = new Trend('hookledger_ingest_latency', true);

export const options = {
  scenarios: {
    ingest: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: 20,
      maxVUs: 80,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<50', 'p(99)<200'],
    hookledger_ingest_latency: ['p(95)<50'],
  },
};

export default function () {
  // __VU and __ITER are unique inside a run; the timestamp avoids collisions across runs.
  const externalId = `k6-${__VU}-${__ITER}-${Date.now()}`;
  const body = JSON.stringify({
    type: 'load.test',
    data: { id: externalId, iteration: __ITER },
  });
  const timestamp = Math.floor(Date.now() / 1000).toString();
  const signature = crypto.hmac('sha256', SECRET, `${timestamp}.${body}`, 'hex');

  const response = http.post(`${BASE_URL}/v1/events`, body, {
    headers: {
      'Content-Type': 'application/json',
      'X-Hook-Source': 'demo',
      'X-Hook-Id': externalId,
      'X-Hook-Type': 'load.test',
      'X-Hook-Timestamp': timestamp,
      'X-Hook-Signature': signature,
    },
    tags: { name: 'ingest' },
  });

  ingestLatency.add(response.timings.duration);
  check(response, {
    'ingest returns 200': (r) => r.status === 200,
    'signature is accepted': (r) => r.status !== 401,
  });

  if (response.status === 200) {
    const payload = response.json();
    if (payload.duplicate) {
      duplicates.add(1);
    } else {
      accepted.add(1);
    }
  }
}
