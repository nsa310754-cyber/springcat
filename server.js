'use strict';

const http = require('node:http');

const HOST = process.env.HOST || '0.0.0.0';
const PORT = Number(process.env.PORT) || 3000;

/**
 * Send a JSON response with the given status code.
 * @param {http.ServerResponse} res
 * @param {number} status
 * @param {unknown} body
 */
function sendJson(res, status, body) {
  const payload = JSON.stringify(body);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(payload),
  });
  res.end(payload);
}

const routes = {
  'GET /': (req, res) => {
    sendJson(res, 200, {
      name: 'springcat',
      message: 'Welcome to the springcat server 🐱',
      endpoints: ['GET /', 'GET /health', 'GET /api/time'],
    });
  },

  'GET /health': (req, res) => {
    sendJson(res, 200, { status: 'ok', uptime: process.uptime() });
  },

  'GET /api/time': (req, res) => {
    sendJson(res, 200, { now: new Date().toISOString() });
  },
};

const server = http.createServer((req, res) => {
  const { pathname } = new URL(req.url, `http://${req.headers.host}`);
  const handler = routes[`${req.method} ${pathname}`];

  if (handler) {
    handler(req, res);
  } else {
    sendJson(res, 404, { error: 'Not Found', path: pathname });
  }
});

// Start the server only when run directly, so it can be imported in tests.
if (require.main === module) {
  server.listen(PORT, HOST, () => {
    console.log(`springcat server listening on http://${HOST}:${PORT}`);
  });

  const shutdown = (signal) => {
    console.log(`\nReceived ${signal}, shutting down...`);
    server.close(() => process.exit(0));
  };
  process.on('SIGINT', () => shutdown('SIGINT'));
  process.on('SIGTERM', () => shutdown('SIGTERM'));
}

module.exports = server;
