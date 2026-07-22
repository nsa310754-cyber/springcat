'use strict';

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const server = require('./server');

let base;

// Start the server on an ephemeral port before running tests.
before(() => new Promise((resolve) => {
  server.listen(0, '127.0.0.1', () => {
    const { port } = server.address();
    base = `http://127.0.0.1:${port}`;
    resolve();
  });
}));

after(() => server.close());

test('GET / returns welcome payload', async () => {
  const res = await fetch(`${base}/`);
  assert.strictEqual(res.status, 200);
  const body = await res.json();
  assert.strictEqual(body.name, 'springcat');
  assert.ok(Array.isArray(body.endpoints));
});

test('GET /health returns ok', async () => {
  const res = await fetch(`${base}/health`);
  assert.strictEqual(res.status, 200);
  const body = await res.json();
  assert.strictEqual(body.status, 'ok');
  assert.strictEqual(typeof body.uptime, 'number');
});

test('GET /api/time returns an ISO timestamp', async () => {
  const res = await fetch(`${base}/api/time`);
  assert.strictEqual(res.status, 200);
  const body = await res.json();
  assert.ok(!Number.isNaN(Date.parse(body.now)));
});

test('unknown route returns 404', async () => {
  const res = await fetch(`${base}/nope`);
  assert.strictEqual(res.status, 404);
  const body = await res.json();
  assert.strictEqual(body.error, 'Not Found');
});
