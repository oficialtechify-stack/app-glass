export default async function handler(req, res) {
  if (req.method !== "POST") {
    res.setHeader("Allow", "POST");
    return res.status(405).json({ error: "Método não permitido" });
  }
  const key = process.env.GROK_API_KEY;
  if (!key) return res.status(503).json({ error: "GROK_API_KEY não configurada no servidor" });
  const { messages } = req.body || {};
  if (!Array.isArray(messages) || !messages.length ||
      messages.length > 30 || messages.some(m => !["user", "assistant"].includes(m.role) || typeof m.content !== "string" || m.content.length > 12000)) {
    return res.status(400).json({ error: "Histórico de conversa inválido" });
  }
  try {
    const upstream = await fetch("https://api.x.ai/v1/chat/completions", {
      method: "POST",
      headers: { Authorization: `Bearer ${key}`, "Content-Type": "application/json" },
      body: JSON.stringify({
        model: process.env.GROK_MODEL || "grok-3-mini",
        messages: [{ role: "system", content: "Você é o assistente do AI Glass OS para óculos RD Glass. Responda em português do Brasil, de forma objetiva. Não afirme controlar funções dos óculos que não foram integradas." }, ...messages],
        stream: false
      })
    });
    const payload = await upstream.json().catch(() => ({}));
    if (!upstream.ok) return res.status(502).json({ error: "Falha no provedor de IA", providerStatus: upstream.status });
    const reply = payload.choices?.[0]?.message?.content;
    if (typeof reply !== "string" || !reply.trim()) return res.status(502).json({ error: "Resposta vazia do provedor" });
    return res.status(200).json({ reply });
  } catch (_) {
    return res.status(502).json({ error: "Não foi possível acessar o provedor de IA" });
  }
}
