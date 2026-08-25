// Cloudflare Pages Function: Gemini プロキシ（/api/gemini）
// APIキーは Cloudflare の環境変数(Secret) GEMINI_KEY から読む。
// クライアント(HTML/APK)にもGitにもキーを置かないためのバックエンド。
export async function onRequestPost({ request, env }) {
  try {
    if (!env.GEMINI_KEY) return json({ error: "GEMINI_KEY が未設定です（Cloudflareの環境変数に設定してください）" }, 500);
    const { model, system, user, maxTokens } = await request.json();
    const m = model || env.GEMINI_MODEL || "gemini-3.6-flash";
    const url = `https://generativelanguage.googleapis.com/v1beta/models/${m}:generateContent?key=${env.GEMINI_KEY}`;
    const r = await fetch(url, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        system_instruction: { parts: [{ text: system || "" }] },
        contents: [{ role: "user", parts: [{ text: user || "" }] }],
        generationConfig: { maxOutputTokens: maxTokens || 4000, temperature: 0.7 },
      }),
    });
    const data = await r.json();
    if (data.error) return json({ error: data.error.message }, r.status);
    const text = (data.candidates?.[0]?.content?.parts || []).map((p) => p.text || "").join("");
    return json({ text });
  } catch (e) {
    return json({ error: String(e && e.message || e) }, 500);
  }
}
const json = (o, s = 200) => new Response(JSON.stringify(o), { status: s, headers: { "Content-Type": "application/json" } });
