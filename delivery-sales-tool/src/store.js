import fs from "fs";
import path from "path";
import { fileURLToPath } from "url";
import { defaultSearchConfig } from "./config.js";

const here = path.dirname(fileURLToPath(import.meta.url));
const dataDir = path.join(here, "..", "data");
const candidatesFile = path.join(dataDir, "candidates.json");
const configFile = path.join(dataDir, "config.json");

function ensureDir() {
  if (!fs.existsSync(dataDir)) fs.mkdirSync(dataDir, { recursive: true });
}

function readJson(file, fallback) {
  try {
    if (!fs.existsSync(file)) return fallback;
    return JSON.parse(fs.readFileSync(file, "utf8"));
  } catch {
    return fallback;
  }
}

function writeJson(file, data) {
  ensureDir();
  fs.writeFileSync(file, JSON.stringify(data, null, 2), "utf8");
}

// ── 検索条件 ───────────────────────────────
export function getConfig() {
  return { ...defaultSearchConfig, ...readJson(configFile, {}) };
}

export function saveConfig(partial) {
  const merged = { ...getConfig(), ...partial };
  writeJson(configFile, merged);
  return merged;
}

// ── 候補者一覧 ─────────────────────────────
export function getCandidates() {
  return readJson(candidatesFile, []);
}

export function getCandidate(id) {
  return getCandidates().find((c) => c.id === id) || null;
}

/**
 * 今回の抽出結果でリストを置き換える。ただし過去に登場した同一ユーザー
 * （username）の DM 送信済み・返信ステータス・生成済み DM は引き継ぐ。
 * これにより検索条件を変えても、その条件の結果だけが一覧に並びつつ、
 * すでに対応済みの候補者の状態は失われない。
 */
export function upsertCandidates(newCandidates) {
  const prevByUser = new Map(getCandidates().map((c) => [c.username, c]));

  const result = newCandidates.map((c) => {
    const prev = prevByUser.get(c.username);
    if (!prev) return c;
    return {
      ...c,
      id: prev.id,
      dmSent: prev.dmSent,
      replyReceived: prev.replyReceived,
      dmText: prev.dmText || c.dmText,
    };
  });

  result.sort((a, b) => b.aiScore - a.aiScore);
  writeJson(candidatesFile, result);
  return result;
}

export function updateCandidate(id, patch) {
  const list = getCandidates();
  const idx = list.findIndex((c) => c.id === id);
  if (idx === -1) return null;
  list[idx] = { ...list[idx], ...patch };
  writeJson(candidatesFile, list);
  return list[idx];
}

export function clearCandidates() {
  writeJson(candidatesFile, []);
  return [];
}
