import type { Env } from "./env";

function json(data: unknown, init: ResponseInit = {}): Response {
  return new Response(JSON.stringify(data), {
    ...init,
    headers: { "Content-Type": "application/json; charset=utf-8", ...(init.headers ?? {}) },
  });
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
    } else {
      stmt = env.DB.prepare(`SELECT * FROM messages WHERE folder = ? ORDER BY created_at DESC LIMIT 100`).bind(
        folder
      );
    }
    const { results } = await stmt.all();
    return json({ messages: (results ?? []).map(rowToSummary) });
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
    if (row.folder !== "trash") {
      return json({ error: "ゴミ箱のメールのみ完全削除できます" }, { status: 400 });
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

  if (path === "/api/send" && request.method === "POST") {
    const body = await request.json<{
      to?: string;
      subject?: string;
      text?: string;
      html?: string;
      inReplyTo?: string;
    }>().catch(() => ({} as any));

    if (!body.to || !body.subject) {
      return json({ error: "宛先(to)と件名(subject)は必須です" }, { status: 400 });
    }
    if (!env.RESEND_API_KEY) {
      return json({ error: "RESEND_API_KEY が設定されていません" }, { status: 500 });
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
        html: body.html,
      }),
    });

    if (!resendRes.ok) {
      const errText = await resendRes.text();
      return json({ error: `送信に失敗しました: ${errText}` }, { status: 502 });
    }

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
        body.text ?? "",
        body.html ?? null,
        (body.text ?? "").slice(0, 160),
        Date.now()
      )
      .run();

    return json({ ok: true });
  }

  return json({ error: "not found" }, { status: 404 });
}
