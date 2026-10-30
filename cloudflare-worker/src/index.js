const SYSTEM_PROMPT = `You are Sundri, a warm, capable personal AI assistant.

LANGUAGE:
- Understand English, Urdu script, and Roman Urdu.
- Reply in the same language and writing style the user used most recently.
- If the user mixes languages, naturally mirror that mix.
- For Roman Urdu, use simple, natural Roman Urdu (not formal Hindi).
- Do not switch to Urdu script unless the user uses Urdu script or asks for it.

STYLE:
- Sound conversational, helpful, and human.
- Keep ordinary voice replies concise (usually 1-3 short sentences).
- Give more detail when the user asks for an explanation or steps.
- Remember and use the conversation history supplied with the request.
- If a request is unclear, ask one short clarifying question.
- Never claim you opened an app, sent a message, made a call, or changed a device setting unless the app confirms that action.
- You are Sundri, not ChatGPT. Do not claim to be a human.
`;

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type",
};

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });
}

export default {
  async fetch(request, env) {
    if (request.method === "OPTIONS") {
      return new Response(null, { headers: corsHeaders });
    }

    const url = new URL(request.url);
    if (request.method === "GET" && url.pathname === "/") {
      return json({ ok: true, service: "Sundri AI Brain", status: "online" });
    }
    if (request.method !== "POST" || url.pathname !== "/chat") {
      return json({ error: "Endpoint not found" }, 404);
    }

    try {
      const body = await request.json();
      const message = String(body.message || "").trim();
      if (!message) return json({ error: "Message is required" }, 400);
      if (message.length > 4000) return json({ error: "Message is too long" }, 400);

      const history = Array.isArray(body.history)
        ? body.history.slice(-8).map(item => ({
            role: item && item.role === "assistant" ? "assistant" : "user",
            content: String((item && item.content) || "").slice(0, 2000),
          })).filter(item => item.content.trim().length > 0)
        : [];

      const result = await env.AI.run("@cf/meta/llama-3.1-8b-instruct-fast", {
        messages: [
          { role: "system", content: SYSTEM_PROMPT },
          ...history,
          { role: "user", content: message },
        ],
        max_tokens: 350,
      });

      const reply = String(result && result.response || "").trim();
      if (!reply) return json({ ok: false, error: "Empty AI response" }, 502);
      return json({ ok: true, reply });
    } catch (error) {
      return json({ ok: false, error: "AI request failed" }, 500);
    }
  },
};
