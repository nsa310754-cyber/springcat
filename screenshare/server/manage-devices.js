#!/usr/bin/env node
'use strict';
/**
 * Small CLI to manage the devices.json allowlist without hand-editing JSON.
 *
 * Usage:
 *   node manage-devices.js add "<display name>" <group> [--print-only]
 *   node manage-devices.js list
 *   node manage-devices.js remove <deviceId>
 */

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const DEVICES_FILE = process.env.DEVICES_FILE || path.join(__dirname, 'devices.json');

function load() {
  if (!fs.existsSync(DEVICES_FILE)) return { devices: [] };
  return JSON.parse(fs.readFileSync(DEVICES_FILE, 'utf8'));
}

function save(data) {
  fs.writeFileSync(DEVICES_FILE, JSON.stringify(data, null, 2) + '\n', { mode: 0o600 });
}

function cmdAdd(name, group) {
  if (!name || !group) {
    console.error('usage: node manage-devices.js add "<display name>" <group>');
    process.exit(1);
  }
  const data = load();
  const id = crypto.randomUUID();
  const secret = crypto.randomBytes(24).toString('hex');
  data.devices.push({ id, secret, name, group });
  save(data);
  console.log('Device enrolled. Enter these values in the ScreenLink app:\n');
  console.log(`  Device ID : ${id}`);
  console.log(`  Secret    : ${secret}`);
  console.log(`  Group     : ${group}`);
  console.log(`\nSaved to ${DEVICES_FILE}. Send SIGHUP to a running server to reload:`);
  console.log(`  pkill -HUP -f server.js   (or) docker kill -s HUP <container>`);
}

function cmdList() {
  const data = load();
  if (!data.devices.length) {
    console.log('(no devices enrolled)');
    return;
  }
  for (const d of data.devices) {
    console.log(`${d.id}  group=${d.group}  ${d.name}`);
  }
}

function cmdRemove(id) {
  if (!id) {
    console.error('usage: node manage-devices.js remove <deviceId>');
    process.exit(1);
  }
  const data = load();
  const before = data.devices.length;
  data.devices = data.devices.filter((d) => d.id !== id);
  if (data.devices.length === before) {
    console.error(`no device with id ${id} found`);
    process.exit(1);
  }
  save(data);
  console.log(`removed ${id}. Reload the running server (SIGHUP) to apply.`);
}

const [, , cmd, ...rest] = process.argv;
switch (cmd) {
  case 'add':
    cmdAdd(rest[0], rest[1]);
    break;
  case 'list':
    cmdList();
    break;
  case 'remove':
    cmdRemove(rest[0]);
    break;
  default:
    console.error('usage: node manage-devices.js <add|list|remove> ...');
    process.exit(1);
}
