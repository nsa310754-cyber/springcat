const state = {
  folder: "inbox",
  query: "",
  messages: [],
  selectedId: null,
};

const el = (id) => document.getElementById(id);

async function api(path, options = {}) {
  const res = await fetch(path, {
    ...options,
    headers: { "Content-Type": "application/json", ...(options.headers ?? {}) },
  });
  if (res.status === 401) {
    showLogin();
    throw new Error("unauthorized");
  }
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new Error(body.error ?? `request failed: ${res.status}`);
  }
  return res.status === 204 ? null : res.json();
}

function showLogin() {
  el("login-screen").hidden = false;
  el("app").hidden = true;
}

function showApp() {
  el("login-screen").hidden = true;
  el("app").hidden = false;
}

async function init() {
  try {
    const session = await api("/api/session");
    el("user-email").textContent = session.email;
    showApp();
    await loadMessages();
  } catch {
    showLogin();
  }
}

el("login-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  el("login-error").hidden = true;
  const password = el("login-password").value;
  try {
    await fetch("/api/login", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ password }),
    }).then(async (res) => {
      if (!res.ok) {
        const body = await res.json().catch(() => ({}));
        throw new Error(body.error ?? "ログインに失敗しました");
      }
    });
    el("login-password").value = "";
    await init();
  } catch (err) {
    el("login-error").textContent = err.message;
    el("login-error").hidden = false;
  }
});

el("logout-btn").addEventListener("click", async () => {
  await fetch("/api/logout", { method: "POST" });
  showLogin();
});

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
    subjectSnippet.innerHTML = `<span class="subject">${escapeHtml(m.subject)}</span> — ${escapeHtml(m.snippet ?? "")}`;

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
  const { message } = await api(`/api/messages/${id}`);
  state.selectedId = id;
  const cached = state.messages.find((m) => m.id === id);
  if (cached) cached.isRead = true;
  renderList();

  el("detail-pane").hidden = false;
  el("star-btn").textContent = message.isStarred ? "★" : "☆";
  el("star-btn").onclick = () => toggleStarDetail(message);
  el("trash-btn").onclick = () => trashMessage(message.id);

  const content = el("detail-content");
  content.innerHTML = "";
  const h2 = document.createElement("h2");
  h2.textContent = message.subject;
  const meta = document.createElement("div");
  meta.className = "detail-meta";
  meta.textContent = `From: ${message.from.name ? message.from.name + " " : ""}<${message.from.address}>  •  ${new Date(message.createdAt).toLocaleString("ja-JP")}`;
  const body = document.createElement("div");
  body.className = "detail-body";
  if (message.bodyHtml) {
    const iframe = document.createElement("iframe");
    iframe.sandbox = "allow-same-origin";
    iframe.srcdoc = message.bodyHtml;
    body.appendChild(iframe);
  } else {
    body.textContent = message.bodyText ?? "";
  }
  content.append(h2, meta, body);
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

init();
