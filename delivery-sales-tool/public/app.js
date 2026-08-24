// ── ちょっとしたヘルパ ──
const $ = (id) => document.getElementById(id);
const api = async (url, opts) => {
  const res = await fetch(url, {
    headers: { "Content-Type": "application/json" },
    ...opts,
  });
  if (!res.ok) throw new Error((await res.json().catch(() => ({}))).error || res.statusText);
  return res.json();
};
const linesToArr = (s) => s.split("\n").map((v) => v.trim()).filter(Boolean);
const arrToLines = (a) => (a || []).join("\n");
const esc = (s) => String(s ?? "").replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));

let candidates = [];
let currentDmId = null;

// ── 起動処理 ──
init();
async function init() {
  await loadStatus();
  await loadConfig();
  await loadCandidates();
  bindEvents();
}

async function loadStatus() {
  try {
    const s = await api("/api/status");
    $("status").innerHTML =
      `<span class="dot ${s.xApi === "connected" ? "on" : "off"}">X API: ${s.xApi === "connected" ? "接続" : "モック"}</span>` +
      `<span class="dot ${s.ai === "connected" ? "on" : "off"}">AI: ${s.ai === "connected" ? s.model : "ルールベース"}</span>`;
  } catch { /* noop */ }
}

async function loadConfig() {
  const c = await api("/api/config");
  $("keywords").value = arrToLines(c.keywords);
  $("regions").value = arrToLines(c.regions);
  $("maxResults").value = c.maxResults;
  $("minScore").value = c.minScore;
  $("ourService").value = c.ourService;
  $("ourPitch").value = c.ourPitch;
  $("langJa").checked = !!c.langJa;
  $("excludeRetweets").checked = !!c.excludeRetweets;
}

function readConfig() {
  return {
    keywords: linesToArr($("keywords").value),
    regions: linesToArr($("regions").value),
    maxResults: Number($("maxResults").value),
    minScore: Number($("minScore").value),
    ourService: $("ourService").value,
    ourPitch: $("ourPitch").value,
    langJa: $("langJa").checked,
    excludeRetweets: $("excludeRetweets").checked,
  };
}

async function loadCandidates() {
  candidates = await api("/api/candidates");
  render();
}

function bindEvents() {
  $("runBtn").onclick = run;
  $("saveBtn").onclick = async () => {
    await api("/api/config", { method: "PUT", body: JSON.stringify(readConfig()) });
    flash($("saveBtn"), "保存しました");
  };
  $("clearBtn").onclick = async () => {
    if (!confirm("候補者一覧をすべて削除しますか？")) return;
    await api("/api/candidates", { method: "DELETE" });
    await loadCandidates();
  };
  $("filterBox").oninput = render;
  $("modalClose").onclick = closeModal;
  $("dmCopy").onclick = copyDm;
  $("dmRegen").onclick = regenDm;
  $("dmSaveSend").onclick = saveAndSend;
  $("modal").onclick = (e) => { if (e.target.id === "modal") closeModal(); };
}

async function run() {
  const btn = $("runBtn");
  btn.disabled = true;
  btn.textContent = "⏳ 実行中...";
  try {
    const result = await api("/api/run", { method: "POST", body: JSON.stringify(readConfig()) });
    candidates = result.candidates;
    const m = result.meta;
    $("runMeta").textContent =
      `収集 ${m.collected}件 → 条件一致 ${m.matched}件（元: ${m.tweetSource === "mock" ? "モック" : "X API"} / 判定: ${m.aiSource === "ai" ? "AI" : "ルールベース"}）`;
    render();
  } catch (e) {
    alert("実行に失敗しました: " + e.message);
  } finally {
    btn.disabled = false;
    btn.textContent = "🔍 検索・抽出を実行";
  }
}

function render() {
  const q = $("filterBox").value.trim().toLowerCase();
  const rows = candidates.filter((c) =>
    !q || (c.name + c.username + c.estimatedRegion + c.tweetText).toLowerCase().includes(q),
  );
  $("count").textContent = `（${rows.length}件${q ? ` / 全${candidates.length}件` : ""}）`;
  $("empty").classList.toggle("hidden", candidates.length > 0);
  $("tbody").innerHTML = rows.map(rowHtml).join("");
  rows.forEach((c) => wireRow(c.id));
}

function scoreClass(s) { return s >= 70 ? "s-hi" : s >= 50 ? "s-mid" : "s-lo"; }

function rowHtml(c) {
  return `<tr data-id="${c.id}">
    <td><span class="score-badge ${scoreClass(c.aiScore)}">${c.aiScore}</span></td>
    <td>
      <div class="acc-name"><a href="${esc(c.profileUrl)}" target="_blank" rel="noopener">${esc(c.name)}</a></div>
      <div class="acc-sub">@${esc(c.username)}</div>
    </td>
    <td class="tweet">${esc(c.tweetText)}<br><a href="${esc(c.tweetUrl)}" target="_blank" rel="noopener">投稿を開く ↗</a></td>
    <td>${Number(c.followers).toLocaleString()}</td>
    <td>${esc(c.estimatedRegion) || "—"}</td>
    <td>${esc(c.estimatedService) || "—"}</td>
    <td class="reason">${esc(c.aiReason)}</td>
    <td><button class="dm-btn ${c.dmText ? "has-dm" : ""}" data-act="dm">${c.dmText ? "DM編集" : "DM生成"}</button></td>
    <td class="status-cell">
      <label><input type="checkbox" data-act="sent" ${c.dmSent ? "checked" : ""}/> 送信済</label>
      <label><input type="checkbox" data-act="reply" ${c.replyReceived ? "checked" : ""}/> 返信あり</label>
    </td>
  </tr>`;
}

function wireRow(id) {
  const tr = document.querySelector(`tr[data-id="${id}"]`);
  if (!tr) return;
  tr.querySelector('[data-act="dm"]').onclick = () => openDm(id);
  tr.querySelector('[data-act="sent"]').onchange = (e) => patch(id, { dmSent: e.target.checked });
  tr.querySelector('[data-act="reply"]').onchange = (e) => patch(id, { replyReceived: e.target.checked });
}

async function patch(id, body) {
  const updated = await api(`/api/candidates/${id}`, { method: "PATCH", body: JSON.stringify(body) });
  const i = candidates.findIndex((c) => c.id === id);
  if (i >= 0) candidates[i] = updated;
}

// ── DM モーダル ──
async function openDm(id) {
  currentDmId = id;
  const c = candidates.find((x) => x.id === id);
  $("modalTitle").textContent = `営業DM文章 — ${c.name}`;
  $("modal").classList.remove("hidden");
  if (c.dmText) {
    $("dmText").value = c.dmText;
  } else {
    $("dmText").value = "生成中...";
    await regenDm();
  }
}

async function regenDm() {
  if (!currentDmId) return;
  const btn = $("dmRegen");
  btn.disabled = true;
  $("dmText").value = "🤖 生成中...";
  try {
    const updated = await api(`/api/candidates/${currentDmId}/dm`, { method: "POST" });
    $("dmText").value = updated.dmText;
    syncLocal(updated);
  } catch (e) {
    $("dmText").value = "生成に失敗しました: " + e.message;
  } finally {
    btn.disabled = false;
  }
}

async function copyDm() {
  try {
    await navigator.clipboard.writeText($("dmText").value);
    flash($("dmCopy"), "コピー完了");
  } catch {
    alert("コピーに失敗しました。手動で選択してください。");
  }
}

async function saveAndSend() {
  if (!currentDmId) return;
  const updated = await api(`/api/candidates/${currentDmId}`, {
    method: "PATCH",
    body: JSON.stringify({ dmText: $("dmText").value, dmSent: true }),
  });
  syncLocal(updated);
  render();
  closeModal();
}

function syncLocal(updated) {
  const i = candidates.findIndex((c) => c.id === updated.id);
  if (i >= 0) candidates[i] = updated;
}

function closeModal() {
  $("modal").classList.add("hidden");
  currentDmId = null;
  render();
}

function flash(btn, msg) {
  const old = btn.textContent;
  btn.textContent = msg;
  setTimeout(() => (btn.textContent = old), 1200);
}
