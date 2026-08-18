const state = {
  folder: "inbox",
  query: "",
  messages: [],
  selectedId: null,
};

const el = (id) => document.getElementById(id);

function reportClientError(payload) {
  try {
    fetch("/api/client-error", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ ...payload, url: location.href, userAgent: navigator.userAgent }),
    }).catch(() => {});
  } catch {
    // best-effort only
  }
}

window.addEventListener("error", (e) => {
  reportClientError({ kind: "error", message: e.message, stack: e.error?.stack });
});
window.addEventListener("unhandledrejection", (e) => {
  reportClientError({ kind: "unhandledrejection", message: String(e.reason?.message ?? e.reason), stack: e.reason?.stack });
});

async function api(path, options = {}) {
  const res = await fetch(path, {
    ...options,
    headers: { "Content-Type": "application/json", ...(options.headers ?? {}) },
  });
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new Error(body.error ?? `request failed: ${res.status}`);
  }
  return res.status === 204 ? null : res.json();
}

async function init() {
  const session = await api("/api/session");
  el("user-email").textContent = session.email;
  el("app").hidden = false;
  await loadMessages();
}

document.querySelectorAll(".folder-item").forEach((btn) => {
  btn.addEventListener("click", () => {
    document.querySelectorAll(".folder-item").forEach((b) => b.classList.remove("active"));
    btn.classList.add("active");
    state.folder = btn.dataset.folder;
    state.query = "";
    el("search-input").value = "";
    closeDetail();
    loadMessages();
  });
});

el("search-form").addEventListener("submit", (e) => {
  e.preventDefault();
  state.query = el("search-input").value.trim();
  loadMessages();
});

el("refresh-btn").addEventListener("click", async () => {
  const btn = el("refresh-btn");
  btn.disabled = true;
  try {
    await loadMessages();
  } finally {
    btn.disabled = false;
  }
});

async function loadMessages() {
  const params = new URLSearchParams({ folder: state.folder });
  if (state.query) params.set("q", state.query);
  const { messages } = await api(`/api/messages?${params.toString()}`);
  state.messages = messages;
  renderList();
}

function formatDate(ts) {
  const d = new Date(ts);
  const now = new Date();
  if (d.toDateString() === now.toDateString()) {
    return d.toLocaleTimeString("ja-JP", { hour: "2-digit", minute: "2-digit" });
  }
  return d.toLocaleDateString("ja-JP", { month: "short", day: "numeric" });
}

function renderList() {
  const list = el("message-list");
  list.innerHTML = "";
  el("list-empty").hidden = state.messages.length > 0;

  for (const m of state.messages) {
    const row = document.createElement("div");
    row.className = "message-row" + (m.isRead ? "" : " unread");
    row.dataset.id = m.id;

    const star = document.createElement("button");
    star.className = "star" + (m.isStarred ? " starred" : "");
    star.textContent = m.isStarred ? "★" : "☆";
    star.addEventListener("click", (e) => {
      e.stopPropagation();
      toggleStar(m);
    });

    const from = document.createElement("div");
    from.className = "from";
    from.textContent = state.folder === "sent" ? m.to : (m.from.name || m.from.address);

    const subjectSnippet = document.createElement("div");
    subjectSnippet.className = "subject-snippet";
    const importantMark = m.isImportant ? `<span class="important-marker">❗</span> ` : "";
    subjectSnippet.innerHTML = `${importantMark}<span class="subject">${escapeHtml(m.subject)}</span> — ${escapeHtml(m.snippet ?? "")}`;

    const date = document.createElement("div");
    date.className = "date";
    date.textContent = formatDate(m.createdAt);

    row.append(star, from, subjectSnippet, date);
    row.addEventListener("click", () => openMessage(m.id));
    list.appendChild(row);
  }
}

function escapeHtml(str) {
  const div = document.createElement("div");
  div.textContent = str ?? "";
  return div.innerHTML;
}

async function toggleStar(m) {
  m.isStarred = !m.isStarred;
  renderList();
  await api(`/api/messages/${m.id}/star`, {
    method: "POST",
    body: JSON.stringify({ starred: m.isStarred }),
  });
}

async function openMessage(id) {
  const content = el("detail-content");
  try {
    const { message } = await api(`/api/messages/${id}`);
    state.selectedId = id;
    const cached = state.messages.find((m) => m.id === id);
    if (cached) cached.isRead = true;
    renderList();

    el("detail-pane").hidden = false;
    el("star-btn").textContent = message.isStarred ? "★" : "☆";
    el("star-btn").onclick = () => toggleStarDetail(message);
    el("important-btn").textContent = message.isImportant ? "❗ 重要解除" : "重要にする";
    el("important-btn").onclick = () => toggleImportantDetail(message);
    el("spam-btn").onclick = () => spamMessage(message.id);
    el("trash-btn").onclick = () => trashMessage(message.id);

    const from = message.from ?? {};
    const h2 = document.createElement("h2");
    h2.textContent = message.subject ?? "(件名なし)";
    const meta = document.createElement("div");
    meta.className = "detail-meta";
    meta.textContent = `From: ${from.name ? from.name + " " : ""}<${from.address ?? "unknown"}>  •  ${new Date(message.createdAt).toLocaleString("ja-JP")}`;
    const body = document.createElement("div");
    body.className = "detail-body";
    if (message.bodyHtml) {
      const iframe = document.createElement("iframe");
      iframe.sandbox = "allow-same-origin";
      iframe.srcdoc = message.bodyHtml;
      iframe.addEventListener("load", () => interceptIframeLinks(iframe));
      body.appendChild(iframe);
    } else {
      body.textContent = message.bodyText ?? "";
    }

    // Build everything first so a mid-render failure can never leave the
    // pane cleared-but-empty; only swap content in once it all succeeded.
    content.replaceChildren(h2, meta, body);
  } catch (err) {
    reportClientError({ kind: "openMessage", message: err.message, stack: err.stack, messageId: id });
    el("detail-pane").hidden = false;
    const errorEl = document.createElement("p");
    errorEl.className = "detail-error";
    errorEl.textContent = `メールの読み込みに失敗しました: ${err.message}`;
    content.replaceChildren(errorEl);
  }
}

// Email bodies render inside a sandboxed iframe. A plain click on a link
// there would navigate the iframe itself, and most real sites refuse to be
// framed (X-Frame-Options / CSP frame-ancestors) — that's the "error" when
// tapping a link. Intercept clicks and open the URL as a real top-level
// navigation instead: a new tab in whatever browser is currently running
// this page, or (inside the Android wrapper) an external browser Intent
// handled natively in MainActivity.java's shouldOverrideUrlLoading.
function interceptIframeLinks(iframe) {
  let doc;
  try {
    doc = iframe.contentDocument;
  } catch {
    return; // cross-origin or otherwise inaccessible; links just won't be interceptable
  }
  if (!doc) return;
  doc.addEventListener("click", (ev) => {
    const link = ev.target.closest("a[href]");
    if (!link) return;
    ev.preventDefault();
    openExternalLink(link.href);
  });
}

function openExternalLink(url) {
  window.open(url, "_blank", "noopener,noreferrer");
}

async function toggleStarDetail(message) {
  message.isStarred = !message.isStarred;
  el("star-btn").textContent = message.isStarred ? "★" : "☆";
  const cached = state.messages.find((m) => m.id === message.id);
  if (cached) cached.isStarred = message.isStarred;
  renderList();
  await api(`/api/messages/${message.id}/star`, {
    method: "POST",
    body: JSON.stringify({ starred: message.isStarred }),
  });
}

async function toggleImportantDetail(message) {
  message.isImportant = !message.isImportant;
  el("important-btn").textContent = message.isImportant ? "❗ 重要解除" : "重要にする";
  const cached = state.messages.find((m) => m.id === message.id);
  if (cached) cached.isImportant = message.isImportant;
  renderList();
  await api(`/api/messages/${message.id}/important`, {
    method: "POST",
    body: JSON.stringify({ important: message.isImportant }),
  });
}

async function spamMessage(id) {
  await api(`/api/messages/${id}/spam`, { method: "POST" });
  closeDetail();
  loadMessages();
}

async function trashMessage(id) {
  await api(`/api/messages/${id}/trash`, { method: "POST" });
  closeDetail();
  loadMessages();
}

function closeDetail() {
  state.selectedId = null;
  el("detail-pane").hidden = true;
}

el("back-btn").addEventListener("click", closeDetail);

// --- Compose ---
el("compose-btn").addEventListener("click", () => {
  el("compose-panel").hidden = false;
  el("compose-status").textContent = "";
});
el("compose-close").addEventListener("click", () => {
  el("compose-panel").hidden = true;
});

el("compose-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const to = el("compose-to").value.trim();
  const subject = el("compose-subject").value.trim() || "(件名なし)";
  const text = el("compose-body").value;
  el("compose-status").textContent = "送信中…";
  try {
    await api("/api/send", {
      method: "POST",
      body: JSON.stringify({ to, subject, text }),
    });
    el("compose-status").textContent = "";
    el("compose-panel").hidden = true;
    el("compose-form").reset();
    if (state.folder === "sent") loadMessages();
  } catch (err) {
    el("compose-status").textContent = err.message;
  }
});

// --- Storage ---
const FOLDER_LABELS = { inbox: "受信トレイ", spam: "迷惑メール", sent: "送信済み", trash: "ゴミ箱" };

function formatBytes(n) {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / (1024 * 1024)).toFixed(2)} MB`;
}

async function openStorage() {
  el("storage-panel").hidden = false;
  const content = el("storage-content");
  content.textContent = "読み込み中…";
  try {
    const stats = await api("/api/storage");
    content.innerHTML = "";
    const rows = [];
    for (const [folder, label] of Object.entries(FOLDER_LABELS)) {
      const info = stats.byFolder[folder] ?? { count: 0, bytes: 0 };
      rows.push({ label, count: info.count });
    }
    rows.push({ label: "合計", count: stats.totalCount, strong: true });
    for (const r of rows) {
      const row = document.createElement("div");
      row.className = "storage-row";
      if (r.strong) row.style.fontWeight = "700";
      row.innerHTML = `<span class="label">${escapeHtml(r.label)}</span><span>${r.count}件</span>`;
      content.appendChild(row);
    }
    const sizeRow = document.createElement("div");
    sizeRow.className = "storage-row";
    sizeRow.innerHTML = `<span class="label">概算サイズ</span><span>${formatBytes(stats.totalBytes)}</span>`;
    content.appendChild(sizeRow);
  } catch (err) {
    content.textContent = `読み込みに失敗しました: ${err.message}`;
  }
}

el("storage-btn").addEventListener("click", openStorage);
el("storage-close").addEventListener("click", () => {
  el("storage-panel").hidden = true;
});
el("empty-trash-btn").addEventListener("click", async () => {
  await api("/api/trash/empty", { method: "POST" });
  if (state.folder === "trash") loadMessages();
  openStorage();
});

// --- Important-mail notifications ---
// Polls every 30s while this tab/app is open and fires a notification for
// new unread "important" mail. No Web Push / service worker / server-side
// push infrastructure — this only works while the app is actually running.
//
// Two backends, picked automatically:
//  - Android wrapper app: a bare WebView has no Notification API at all, so
//    MainActivity.java exposes window.AndroidNotify, a bridge to a real
//    Android system notification.
//  - Regular browser: the standard Notification API.
const NOTIFIED_IDS_KEY = "springcat-mail-notified-ids";
const NOTIFY_POLL_MS = 30000;

const hasAndroidBridge = typeof window.AndroidNotify !== "undefined";
const hasBrowserNotifications = "Notification" in window;
const notificationsSupported = hasAndroidBridge || hasBrowserNotifications;

async function notifyHasPermission() {
  if (hasAndroidBridge) return window.AndroidNotify.hasPermission();
  if (hasBrowserNotifications) return Notification.permission === "granted";
  return false;
}

async function notifyRequestPermission() {
  if (hasAndroidBridge) {
    window.AndroidNotify.requestPermission();
    // The Android permission dialog result arrives asynchronously with no
    // callback into JS, so give the user a moment to respond before we
    // re-check hasPermission() for the button label.
    await new Promise((resolve) => setTimeout(resolve, 1200));
    return notifyHasPermission();
  }
  if (hasBrowserNotifications) {
    const result = await Notification.requestPermission();
    return result === "granted";
  }
  return false;
}

function showNotification(title, body, tag) {
  if (hasAndroidBridge) {
    window.AndroidNotify.postNotification(title, body, tag);
    return;
  }
  const n = new Notification(title, { body, tag });
  n.onclick = () => {
    window.focus();
    openMessage(tag);
  };
}

function loadNotifiedIds() {
  try {
    return new Set(JSON.parse(localStorage.getItem(NOTIFIED_IDS_KEY) ?? "[]"));
  } catch {
    return new Set();
  }
}

function saveNotifiedIds(ids) {
  // Cap stored history so it can't grow unbounded.
  const arr = Array.from(ids).slice(-500);
  localStorage.setItem(NOTIFIED_IDS_KEY, JSON.stringify(arr));
}

async function updateNotifyButton() {
  const btn = el("notify-btn");
  if (!notificationsSupported) {
    btn.textContent = "🔔 このアプリは非対応";
    btn.disabled = true;
    return;
  }
  btn.textContent = (await notifyHasPermission()) ? "🔔 通知オン" : "🔔 通知をオンにする";
}

async function pollImportantMail() {
  if (!notificationsSupported || !(await notifyHasPermission())) return;
  let messages;
  try {
    ({ messages } = await api("/api/messages?folder=important"));
  } catch {
    return; // best-effort; try again next interval
  }
  const notified = loadNotifiedIds();
  for (const m of messages) {
    if (m.isRead || notified.has(m.id)) continue;
    notified.add(m.id);
    showNotification(`❗ ${m.subject}`, `${m.from.name || m.from.address}: ${m.snippet ?? ""}`, m.id);
  }
  saveNotifiedIds(notified);
}

el("notify-btn").addEventListener("click", async () => {
  if (!notificationsSupported) return;
  if (!(await notifyHasPermission())) {
    await notifyRequestPermission();
  }
  await updateNotifyButton();
  if (await notifyHasPermission()) pollImportantMail();
});

if (notificationsSupported) {
  updateNotifyButton();
  // Seed already-known important mail as "notified" so turning this on
  // doesn't immediately fire a notification for everything already in
  // the inbox — only genuinely new mail triggers one from here on.
  if (localStorage.getItem(NOTIFIED_IDS_KEY) === null) {
    api("/api/messages?folder=important")
      .then(({ messages }) => saveNotifiedIds(new Set(messages.map((m) => m.id))))
      .catch(() => {});
  }
  setInterval(pollImportantMail, NOTIFY_POLL_MS);
}

// Force a real reload when the page is restored from the browser's
// back/forward cache, so a stale rendered state (e.g. a broken detail
// pane from before a fix was deployed) never lingers silently.
window.addEventListener("pageshow", (e) => {
  if (e.persisted) location.reload();
});

init();
