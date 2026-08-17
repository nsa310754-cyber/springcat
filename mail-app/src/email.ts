import PostalMime from "postal-mime";
import type { Env } from "./env";

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
  const subject = parsed.subject ?? "(件名なし)";
  const important = looksImportant(subject, parsed.text ?? "");

  await env.DB.prepare(
    `INSERT INTO messages
      (id, folder, thread_id, from_addr, from_name, to_addr, reply_to, subject,
       body_text, body_html, snippet, is_read, is_starred, is_important, created_at)
     VALUES (?, 'inbox', ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, ?, ?)`
  )
    .bind(
      crypto.randomUUID(),
      parsed.messageId ?? crypto.randomUUID(),
      fromAddr,
      fromName,
      message.to,
      parsed.replyTo?.[0]?.address ?? null,
      subject,
      parsed.text ?? null,
      parsed.html ?? null,
      snippet,
      important ? 1 : 0,
      Date.now()
    )
    .run();
}

// Best-effort heuristic: flag verification/auth-code mail as important so it
// doesn't get lost in the inbox. False positives just add a tag (harmless);
// there's no attempt at real spam/priority classification here.
const IMPORTANT_PATTERNS = [
  /認証コード/, /認証番号/, /確認コード/, /確認番号/, /ワンタイムパスワード/,
  /二段階認証/, /二要素認証/, /本人確認/, /パスワード(の)?(再設定|リセット)/,
  /verification code/i, /security code/i, /one-time password/i, /one time password/i,
  /\botp\b/i, /2fa/i, /two-factor/i, /confirm your (email|account)/i,
  /reset your password/i, /login code/i, /sign-?in code/i,
];

function looksImportant(subject: string, bodyText: string): boolean {
  const haystack = `${subject}\n${bodyText.slice(0, 2000)}`;
  return IMPORTANT_PATTERNS.some((re) => re.test(haystack));
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
