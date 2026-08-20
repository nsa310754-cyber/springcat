import type { Env } from "./env";

function json(data: unknown, init: ResponseInit = {}): Response {
  return new Response(JSON.stringify(data), {
    ...init,
    headers: { "Content-Type": "application/json; charset=utf-8", ...(init.headers ?? {}) },
  });
}

function escapeHtmlServer(str: string): string {
  return str
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

function rowToSummary(row: any) {
  return {
    id: row.id,
    from: { address: row.from_addr, name: row.from_name },
    to: row.to_addr,
    subject: row.subject,
    snippet: row.snippet,
    isRead: !!row.is_read,
    isStarred: !!row.is_starred,
    isImportant: !!row.is_important,
    createdAt: row.created_at,
    folder: row.folder,
  };
}

function rowToDetail(row: any) {
  return {
    ...rowToSummary(row),
    bodyText: row.body_text,
    bodyHtml: row.body_html,
    replyTo: row.reply_to,
    threadId: row.thread_id,
  };
}

export async function handleApi(request: Request, env: Env, path: string): Promise<Response> {
  if (path === "/api/client-error" && request.method === "POST") {
    const body = await request.json<Record<string, unknown>>().catch(() => ({}));
    console.error("[client-error]", JSON.stringify(body));
    return json({ ok: true });
  }

  if (path === "/api/session" && request.method === "GET") {
    return json({ authenticated: true, email: env.APP_EMAIL });
  }

  if (path === "/api/translate" && request.method === "POST") {
    const body = await request.json<{ text?: string }>().catch(() => ({} as any));
    const text = (body.text ?? "").trim();
    if (!text) return json({ error: "翻訳するテキストがありません" }, { status: 400 });
    try {
      const result = await translateToJapanese(text);
      return json(result);
    } catch (err) {
      return json({ error: `翻訳に失敗しました: ${(err as Error).message}` }, { status: 502 });
    }
  }

  if (path === "/api/messages" && request.method === "GET") {
    const url = new URL(request.url);
    const folder = url.searchParams.get("folder") ?? "inbox";
    const q = url.searchParams.get("q");
    let stmt;
    if (q) {
      const like = `%${q}%`;
      stmt = env.DB.prepare(
        `SELECT * FROM messages WHERE folder = ? AND (subject LIKE ? OR from_addr LIKE ? OR body_text LIKE ?)
         ORDER BY created_at DESC LIMIT 100`
      ).bind(folder, like, like, like);
    } else if (folder === "starred") {
      stmt = env.DB.prepare(`SELECT * FROM messages WHERE is_starred = 1 ORDER BY created_at DESC LIMIT 100`);
    } else if (folder === "important") {
      stmt = env.DB.prepare(`SELECT * FROM messages WHERE is_important = 1 ORDER BY created_at DESC LIMIT 100`);
    } else {
      stmt = env.DB.prepare(`SELECT * FROM messages WHERE folder = ? ORDER BY created_at DESC LIMIT 100`).bind(
        folder
      );
    }
    const { results } = await stmt.all();
    return json({ messages: (results ?? []).map(rowToSummary) });
  }

  if (path === "/api/storage" && request.method === "GET") {
    const { results } = await env.DB.prepare(
      `SELECT folder, COUNT(*) as count, COALESCE(SUM(LENGTH(body_text) + LENGTH(body_html)), 0) as bytes
       FROM messages GROUP BY folder`
    ).all<{ folder: string; count: number; bytes: number }>();
    const byFolder: Record<string, { count: number; bytes: number }> = {};
    let totalCount = 0;
    let totalBytes = 0;
    for (const row of results ?? []) {
      byFolder[row.folder] = { count: row.count, bytes: row.bytes };
      totalCount += row.count;
      totalBytes += row.bytes;
    }
    return json({ byFolder, totalCount, totalBytes });
  }

  if (path === "/api/trash/empty" && request.method === "POST") {
    const result = await env.DB.prepare(`DELETE FROM messages WHERE folder = 'trash'`).run();
    return json({ ok: true, deleted: result.meta.changes ?? 0 });
  }

  const detailMatch = path.match(/^\/api\/messages\/([^/]+)$/);
  if (detailMatch && request.method === "GET") {
    const id = detailMatch[1];
    const row = await env.DB.prepare(`SELECT * FROM messages WHERE id = ?`).bind(id).first();
    if (!row) return json({ error: "not found" }, { status: 404 });
    if (!row.is_read) {
      await env.DB.prepare(`UPDATE messages SET is_read = 1 WHERE id = ?`).bind(id).run();
      row.is_read = 1;
    }
    return json({ message: rowToDetail(row) });
  }

  if (detailMatch && request.method === "DELETE") {
    const id = detailMatch[1];
    const row = await env.DB.prepare(`SELECT folder FROM messages WHERE id = ?`).bind(id).first();
    if (!row) return json({ error: "not found" }, { status: 404 });
    if (row.folder !== "trash" && row.folder !== "spam") {
      return json({ error: "ゴミ箱または迷惑メールのメールのみ完全削除できます" }, { status: 400 });
    }
    await env.DB.prepare(`DELETE FROM messages WHERE id = ?`).bind(id).run();
    return json({ ok: true });
  }

  const starMatch = path.match(/^\/api\/messages\/([^/]+)\/star$/);
  if (starMatch && request.method === "POST") {
    const { starred } = await request.json<{ starred: boolean }>().catch(() => ({ starred: true }));
    await env.DB.prepare(`UPDATE messages SET is_starred = ? WHERE id = ?`)
      .bind(starred ? 1 : 0, starMatch[1])
      .run();
    return json({ ok: true });
  }

  const importantMatch = path.match(/^\/api\/messages\/([^/]+)\/important$/);
  if (importantMatch && request.method === "POST") {
    const { important } = await request.json<{ important: boolean }>().catch(() => ({ important: true }));
    await env.DB.prepare(`UPDATE messages SET is_important = ? WHERE id = ?`)
      .bind(important ? 1 : 0, importantMatch[1])
      .run();
    return json({ ok: true });
  }

  const trashMatch = path.match(/^\/api\/messages\/([^/]+)\/trash$/);
  if (trashMatch && request.method === "POST") {
    await env.DB.prepare(`UPDATE messages SET folder = 'trash' WHERE id = ?`).bind(trashMatch[1]).run();
    return json({ ok: true });
  }

  const untrashMatch = path.match(/^\/api\/messages\/([^/]+)\/untrash$/);
  if (untrashMatch && request.method === "POST") {
    await env.DB.prepare(`UPDATE messages SET folder = 'inbox' WHERE id = ?`).bind(untrashMatch[1]).run();
    return json({ ok: true });
  }

  const spamMatch = path.match(/^\/api\/messages\/([^/]+)\/spam$/);
  if (spamMatch && request.method === "POST") {
    await env.DB.prepare(`UPDATE messages SET folder = 'spam' WHERE id = ?`).bind(spamMatch[1]).run();
    return json({ ok: true });
  }

  const unspamMatch = path.match(/^\/api\/messages\/([^/]+)\/unspam$/);
  if (unspamMatch && request.method === "POST") {
    await env.DB.prepare(`UPDATE messages SET folder = 'inbox' WHERE id = ?`).bind(unspamMatch[1]).run();
    return json({ ok: true });
  }

  if (path === "/api/send" && request.method === "POST") {
    const body = await request.json<{
      to?: string;
      subject?: string;
      text?: string;
      html?: string;
      inReplyTo?: string;
      attachments?: { filename: string; content: string; contentType?: string }[];
    }>().catch(() => ({} as any));

    if (!body.to || !body.subject) {
      return json({ error: "宛先(to)と件名(subject)は必須です" }, { status: 400 });
    }
    if (!env.RESEND_API_KEY) {
      return json({ error: "RESEND_API_KEY が設定されていません" }, { status: 500 });
    }

    type Attachment = { filename: string; content: string; contentType?: string };
    const attachments: Attachment[] = Array.isArray(body.attachments) ? body.attachments : [];
    // Guard total attachment size (base64 is ~4/3 of the raw bytes).
    const totalBase64 = attachments.reduce((n: number, a: Attachment) => n + (a.content?.length ?? 0), 0);
    if (totalBase64 > 20 * 1024 * 1024) {
      return json({ error: "添付ファイルの合計サイズが大きすぎます（約15MBまで）" }, { status: 400 });
    }

    // Build an HTML body that embeds the images inline so the recipient
    // sees them in the message, in addition to receiving them as files.
    let html = body.html;
    if (attachments.length > 0) {
      const textHtml = escapeHtmlServer(body.text ?? "").replace(/\n/g, "<br>");
      const imgs = attachments
        .map(
          (a) =>
            `<div style="margin-top:12px"><img src="data:${a.contentType ?? "image/png"};base64,${a.content}" alt="${escapeHtmlServer(a.filename)}" style="max-width:100%;height:auto;border-radius:8px"></div>`
        )
        .join("");
      html = `<div>${textHtml}</div>${imgs}`;
    }

    const resendRes = await fetch("https://api.resend.com/emails", {
      method: "POST",
      headers: {
        Authorization: `Bearer ${env.RESEND_API_KEY}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        from: env.APP_EMAIL,
        to: [body.to],
        subject: body.subject,
        text: body.text ?? "",
        html,
        attachments: attachments.map((a) => ({ filename: a.filename, content: a.content })),
      }),
    });

    if (!resendRes.ok) {
      const errText = await resendRes.text();
      return json({ error: `送信に失敗しました: ${errText}` }, { status: 502 });
    }

    // For the stored "sent" copy, keep the inline-image HTML only when it's
    // small enough to be worth storing in D1; otherwise just note the count.
    const storedHtml = html && html.length <= 700 * 1024 ? html : body.html ?? null;
    const attachNote = attachments.length > 0 ? `　[画像${attachments.length}枚を添付]` : "";

    await env.DB.prepare(
      `INSERT INTO messages
        (id, folder, thread_id, from_addr, from_name, to_addr, subject, body_text, body_html, snippet, is_read, is_starred, created_at)
       VALUES (?, 'sent', ?, ?, ?, ?, ?, ?, ?, ?, 1, 0, ?)`
    )
      .bind(
        crypto.randomUUID(),
        body.inReplyTo ?? crypto.randomUUID(),
        env.APP_EMAIL,
        "springcat",
        body.to,
        body.subject,
        (body.text ?? "") + (attachments.length ? `\n\n[添付: ${attachments.map((a) => a.filename).join(", ")}]` : ""),
        storedHtml,
        ((body.text ?? "").slice(0, 150) + attachNote).slice(0, 160),
        Date.now()
      )
      .run();

    return json({ ok: true });
  }

  return json({ error: "not found" }, { status: 404 });
}

// Translate arbitrary text into Japanese using Google's public "gtx"
// translate endpoint (no API key). Source language is auto-detected and
// returned so the caller can decide whether translation was even needed.
// Long text is split into chunks to stay under the GET URL length limit.
async function translateToJapanese(
  text: string
): Promise<{ translated: string; detectedLang: string }> {
  const chunks = splitForTranslate(text, 1500);
  let translated = "";
  let detectedLang = "";
  for (const chunk of chunks) {
    const url =
      "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=ja&dt=t&q=" +
      encodeURIComponent(chunk);
    const res = await fetch(url, { headers: { "User-Agent": "Mozilla/5.0" } });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const data = (await res.json()) as any;
    // data[0] is an array of [translatedSegment, originalSegment, ...].
    for (const seg of data[0] ?? []) {
      if (seg?.[0]) translated += seg[0];
    }
    if (!detectedLang && typeof data[2] === "string") detectedLang = data[2];
  }
  return { translated, detectedLang };
}

// Split text into pieces no longer than maxLen, preferring to break on
// newlines and then spaces so words/sentences aren't cut mid-way.
function splitForTranslate(text: string, maxLen: number): string[] {
  const capped = text.slice(0, 8000); // hard safety cap on total length
  const chunks: string[] = [];
  let remaining = capped;
  while (remaining.length > maxLen) {
    let cut = remaining.lastIndexOf("\n", maxLen);
    if (cut < maxLen * 0.5) cut = remaining.lastIndexOf(" ", maxLen);
    if (cut < maxLen * 0.5) cut = maxLen;
    chunks.push(remaining.slice(0, cut));
    remaining = remaining.slice(cut);
  }
  if (remaining.trim()) chunks.push(remaining);
  return chunks;
}
