import PostalMime from "postal-mime";
import type { Env } from "./auth";

// Cloudflare Email Routing invokes this handler for every message
// addressed to springcat@ragdollp.site once a routing rule points here.
export async function handleIncomingEmail(
  message: ForwardableEmailMessage,
  env: Env
): Promise<void> {
  const rawBuffer = await streamToArrayBuffer(message.raw, message.rawSize);
  const parsed = await PostalMime.parse(rawBuffer);

  const fromAddr = parsed.from?.address ?? message.from ?? "unknown@unknown";
  const fromName = parsed.from?.name ?? "";
  const snippetSource = (parsed.text ?? stripHtml(parsed.html ?? "")).trim();
  const snippet = snippetSource.slice(0, 160);

  await env.DB.prepare(
    `INSERT INTO messages
      (id, folder, thread_id, from_addr, from_name, to_addr, reply_to, subject,
       body_text, body_html, snippet, is_read, is_starred, created_at)
     VALUES (?, 'inbox', ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, ?)`
  )
    .bind(
      crypto.randomUUID(),
      parsed.messageId ?? crypto.randomUUID(),
      fromAddr,
      fromName,
      message.to,
      parsed.replyTo?.[0]?.address ?? null,
      parsed.subject ?? "(件名なし)",
      parsed.text ?? null,
      parsed.html ?? null,
      snippet,
      Date.now()
    )
    .run();
}

async function streamToArrayBuffer(stream: ReadableStream, size: number): Promise<ArrayBuffer> {
  const reader = stream.getReader();
  const buffer = new Uint8Array(size);
  let offset = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer.set(value, offset);
    offset += value.length;
  }
  return buffer.buffer;
}

function stripHtml(html: string): string {
  return html.replace(/<[^>]*>/g, " ").replace(/\s+/g, " ");
}
