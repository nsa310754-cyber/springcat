import express from "express";
import path from "path";
import { fileURLToPath } from "url";
import { config, hasXApi, hasAi } from "./src/config.js";
import { runPipeline } from "./src/pipeline.js";
import { generateDm } from "./src/aiClient.js";
import {
  getConfig,
  saveConfig,
  getCandidates,
  getCandidate,
  updateCandidate,
  clearCandidates,
} from "./src/store.js";

const here = path.dirname(fileURLToPath(import.meta.url));
const app = express();
app.use(express.json());
app.use(express.static(path.join(here, "public")));

/** 稼働状況（モックか実 API か）を返す */
app.get("/api/status", (_req, res) => {
  res.json({
    xApi: hasXApi() ? "connected" : "mock",
    ai: hasAi() ? "connected" : "heuristic",
    model: config.aiModel,
  });
});

/** 現在の検索条件を取得 */
app.get("/api/config", (_req, res) => {
  res.json(getConfig());
});

/** 検索条件を保存 */
app.put("/api/config", (req, res) => {
  const merged = saveConfig(sanitizeConfig(req.body));
  res.json(merged);
});

/** 検索 → AI 判定 → 一覧化 を実行 */
app.post("/api/run", async (req, res) => {
  try {
    // リクエストに条件が含まれていれば保存してから実行
    const search = req.body && Object.keys(req.body).length
      ? saveConfig(sanitizeConfig(req.body))
      : getConfig();
    const result = await runPipeline(search);
    res.json(result);
  } catch (err) {
    console.error("[/api/run]", err);
    res.status(500).json({ error: err.message });
  }
});

/** 候補者一覧を取得 */
app.get("/api/candidates", (_req, res) => {
  res.json(getCandidates());
});

/** 候補者のステータス更新（DM送信済/返信あり など） */
app.patch("/api/candidates/:id", (req, res) => {
  const allowed = {};
  if ("dmSent" in req.body) allowed.dmSent = Boolean(req.body.dmSent);
  if ("replyReceived" in req.body) allowed.replyReceived = Boolean(req.body.replyReceived);
  if ("dmText" in req.body) allowed.dmText = String(req.body.dmText);
  const updated = updateCandidate(req.params.id, allowed);
  if (!updated) return res.status(404).json({ error: "not found" });
  res.json(updated);
});

/** 候補者向けの営業 DM を生成して保存 */
app.post("/api/candidates/:id/dm", async (req, res) => {
  try {
    const candidate = getCandidate(req.params.id);
    if (!candidate) return res.status(404).json({ error: "not found" });
    const search = getConfig();
    const dmText = await generateDm(candidate, search);
    const updated = updateCandidate(req.params.id, { dmText });
    res.json(updated);
  } catch (err) {
    console.error("[/api/candidates/:id/dm]", err);
    res.status(500).json({ error: err.message });
  }
});

/** 一覧をクリア */
app.delete("/api/candidates", (_req, res) => {
  res.json(clearCandidates());
});

function sanitizeConfig(body = {}) {
  const out = {};
  if (Array.isArray(body.keywords)) out.keywords = body.keywords.map(String).filter(Boolean);
  if (Array.isArray(body.regions)) out.regions = body.regions.map(String).filter(Boolean);
  if (body.maxResults != null) out.maxResults = clampInt(body.maxResults, 10, 100);
  if (body.minScore != null) out.minScore = clampInt(body.minScore, 0, 100);
  if (body.langJa != null) out.langJa = Boolean(body.langJa);
  if (body.excludeRetweets != null) out.excludeRetweets = Boolean(body.excludeRetweets);
  if (body.ourService != null) out.ourService = String(body.ourService);
  if (body.ourPitch != null) out.ourPitch = String(body.ourPitch);
  return out;
}

function clampInt(n, min, max) {
  const v = Math.round(Number(n));
  if (Number.isNaN(v)) return min;
  return Math.max(min, Math.min(max, v));
}

app.listen(config.port, () => {
  console.log(`\n  配達員営業リスト作成ツール`);
  console.log(`  → http://localhost:${config.port}`);
  console.log(`  X API: ${hasXApi() ? "接続" : "モック"} / AI: ${hasAi() ? `接続 (${config.aiModel})` : "ルールベース"}\n`);
});
