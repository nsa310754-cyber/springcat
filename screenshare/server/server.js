'use strict';
/**
 * ScreenLink relay/pairing server.
 *
 * Scope, on purpose:
 *  - Only relays screen-frame bytes 1:1 between two devices that are BOTH
 *    pre-enrolled by an admin in devices.json (allowlist, not an open signup).
 *  - No remote command execution, no input injection, no file access -
 *    this is a view-only screen mirroring relay, not a general C2 channel.
 *  - Never stores or logs frame contents.
 *
 * Run behind TLS (wss://) in production - e.g. a reverse proxy (nginx/caddy)
 * terminating TLS and forwarding to this process on localhost. Do not expose
 * plain ws:// on the public internet.
 */

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { WebSocketServer } = require('ws');

const PORT = parseInt(process.env.PORT || '8787', 10);
const DEVICES_FILE = process.env.DEVICES_FILE || path.join(__dirname, 'devices.json');
const HELLO_TIMEOUT_MS = 5000;

function loadAllowlist() {
  if (!fs.existsSync(DEVICES_FILE)) {
    console.error(
      `[fatal] ${DEVICES_FILE} not found. Copy devices.example.json to devices.json ` +
        `and enroll your permitted devices before starting the server.`
    );
    process.exit(1);
  }
  const raw = JSON.parse(fs.readFileSync(DEVICES_FILE, 'utf8'));
  const map = new Map();
  for (const d of raw.devices || []) {
    if (!d.id || !d.secret || !d.name || !d.group) continue;
    map.set(d.id, d);
  }
  return map;
}

let allowlist = loadAllowlist();
// Re-read the allowlist on SIGHUP so an admin can add/revoke devices without downtime.
process.on('SIGHUP', () => {
  try {
    allowlist = loadAllowlist();
    console.log(`[allowlist] reloaded, ${allowlist.size} device(s) enrolled`);
  } catch (e) {
    console.error('[allowlist] reload failed, keeping previous list:', e.message);
  }
});

/** deviceId -> { ws, name, group } for currently connected, authenticated devices */
const online = new Map();
/** deviceId -> peerDeviceId, for an active accepted viewing session (both directions stored) */
const sessions = new Map();

function safeSend(ws, obj) {
  if (ws.readyState === ws.OPEN) ws.send(JSON.stringify(obj));
}

function timingSafeEqual(a, b) {
  const bufA = Buffer.from(String(a));
  const bufB = Buffer.from(String(b));
  if (bufA.length !== bufB.length) return false;
  return crypto.timingSafeEqual(bufA, bufB);
}

function rosterFor(deviceId) {
  const self = allowlist.get(deviceId);
  if (!self) return [];
  const out = [];
  for (const [id, info] of online) {
    if (id === deviceId) continue;
    if (info.group !== self.group) continue;
    out.push({ id, name: info.name, sharing: sessions.has(id) === false && info.canHost !== false });
  }
  return out;
}

function broadcastRoster(group) {
  for (const [id, info] of online) {
    if (info.group !== group) continue;
    safeSend(info.ws, { type: 'roster', devices: rosterFor(id) });
  }
}

function endSession(deviceId, notifyPeer) {
  const peerId = sessions.get(deviceId);
  if (!peerId) return;
  sessions.delete(deviceId);
  sessions.delete(peerId);
  if (notifyPeer) {
    const peer = online.get(peerId);
    if (peer) safeSend(peer.ws, { type: 'session-ended', peerId: deviceId });
  }
}

const wss = new WebSocketServer({ port: PORT });

wss.on('connection', (ws, req) => {
  let deviceId = null;
  let helloTimer = setTimeout(() => {
    try {
      ws.close(4008, 'hello timeout');
    } catch (_) {}
  }, HELLO_TIMEOUT_MS);

  ws.on('message', (data, isBinary) => {
    // Before authentication, only accept the JSON "hello" handshake.
    if (!deviceId) {
      if (isBinary) return ws.close(4000, 'expected hello');
      let msg;
      try {
        msg = JSON.parse(data.toString('utf8'));
      } catch (_) {
        return ws.close(4000, 'bad hello');
      }
      if (msg.type !== 'hello' || !msg.id || !msg.secret) {
        return ws.close(4000, 'bad hello');
      }
      const entry = allowlist.get(msg.id);
      if (!entry || !timingSafeEqual(entry.secret, msg.secret)) {
        console.warn(`[auth] rejected unrecognized device id=${msg.id}`);
        return ws.close(4001, 'not permitted');
      }
      if (online.has(msg.id)) {
        return ws.close(4002, 'already connected elsewhere');
      }
      clearTimeout(helloTimer);
      deviceId = msg.id;
      online.set(deviceId, { ws, name: entry.name, group: entry.group });
      console.log(`[connect] ${entry.name} (${deviceId}) group=${entry.group}`);
      safeSend(ws, { type: 'hello-ok', id: deviceId, name: entry.name, group: entry.group });
      broadcastRoster(entry.group);
      return;
    }

    // Binary frames: relay to the paired peer in an active session only.
    if (isBinary) {
      const peerId = sessions.get(deviceId);
      if (!peerId) return;
      const peer = online.get(peerId);
      if (peer && peer.ws.readyState === peer.ws.OPEN) peer.ws.send(data, { binary: true });
      return;
    }

    let msg;
    try {
      msg = JSON.parse(data.toString('utf8'));
    } catch (_) {
      return;
    }
    const self = allowlist.get(deviceId);

    switch (msg.type) {
      case 'list': {
        safeSend(ws, { type: 'roster', devices: rosterFor(deviceId) });
        break;
      }
      case 'request-view': {
        const target = online.get(msg.targetId);
        if (!target || target.group !== self.group) {
          safeSend(ws, { type: 'view-response', fromId: msg.targetId, accept: false, reason: 'offline' });
          break;
        }
        safeSend(target.ws, { type: 'incoming-view-request', fromId: deviceId, fromName: self.name });
        break;
      }
      case 'respond-view': {
        // Host responding to a viewer's request.
        const viewer = online.get(msg.targetId);
        if (!viewer) break;
        if (msg.accept) {
          sessions.set(deviceId, msg.targetId);
          sessions.set(msg.targetId, deviceId);
        }
        safeSend(viewer.ws, { type: 'view-response', fromId: deviceId, accept: !!msg.accept });
        break;
      }
      case 'stop-share': {
        endSession(deviceId, true);
        break;
      }
      default:
        break;
    }
  });

  ws.on('close', () => {
    clearTimeout(helloTimer);
    if (!deviceId) return;
    const info = online.get(deviceId);
    online.delete(deviceId);
    endSession(deviceId, true);
    if (info) {
      console.log(`[disconnect] ${info.name} (${deviceId})`);
      broadcastRoster(info.group);
    }
  });

  ws.on('error', () => {});
});

console.log(`[screenlink-relay] listening on ws://0.0.0.0:${PORT}`);
console.log(`[screenlink-relay] ${allowlist.size} device(s) enrolled from ${DEVICES_FILE}`);
console.log('[screenlink-relay] put this behind TLS (wss://) before using over the internet');
