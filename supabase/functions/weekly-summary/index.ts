// GoldenPaw weekly summary: turns a structured week digest into a short, kind, NON-diagnostic recap.
//
// Deploy:   supabase functions deploy weekly-summary
// Secrets:  supabase secrets set ANTHROPIC_API_KEY=sk-ant-...   (optional: ANTHROPIC_MODEL)
//
// The app sends only aggregated numbers and the owner's own notes (no photos, contact details or ids).
// Requests must carry a valid Supabase JWT (default for Edge Functions), so only signed-in users can
// call it; the app falls back to its on-device writer when this fails.

const MODEL = Deno.env.get("ANTHROPIC_MODEL") ?? "claude-haiku-4-5-20251001";
const API_KEY = Deno.env.get("ANTHROPIC_API_KEY");

const SYSTEM = `You write a short weekly recap for a pet owner who cares for an older or chronically ill dog or cat.
You receive one week of logged data as JSON. Write warmly, plainly and briefly, like a thoughtful friend who is good with numbers.

Hard rules:
- You are not a veterinarian. Never diagnose, name possible conditions, interpret causes, or suggest changing, stopping or dosing any medication.
- Only describe what the data shows (counts, trends, comparisons with last week). Don't invent facts. If data is thin, say so kindly.
- "watch_items" are neutral observations worth keeping an eye on (e.g. "Limping was logged on 3 days"). No speculation.
- "vet_questions" are open questions the owner could ask their vet, phrased neutrally (e.g. "Is the change in appetite worth looking at?"). At most 3. Leave empty if nothing stands out.
- If anything looks urgent (e.g. many hard days, not eating several days), add a watch item suggesting they contact their vet. Don't alarm.
- Use the pet's name. No emojis. No medical jargon. British or American spelling is fine.

Respond with ONLY a JSON object, no markdown:
{"headline": string (max 70 chars), "summary": string (2-4 sentences), "highlights": string[] (max 3), "watch_items": string[] (max 3), "vet_questions": string[] (max 3)}`;

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { ...cors, "Content-Type": "application/json" } });
}

function clean(list: unknown, max: number): string[] {
  return Array.isArray(list) ? list.filter((x) => typeof x === "string" && x.trim()).map((x) => x.trim()).slice(0, max) : [];
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (req.method !== "POST") return json({ message: "POST only" }, 405);
  if (!API_KEY) return json({ message: "Summaries aren't configured on the server" }, 503);

  let digest: Record<string, unknown>;
  try {
    digest = await req.json();
  } catch {
    return json({ message: "Invalid JSON" }, 400);
  }
  // Keep the prompt small and predictable.
  const payload = JSON.stringify(digest).slice(0, 8000);

  const res = await fetch("https://api.anthropic.com/v1/messages", {
    method: "POST",
    headers: {
      "x-api-key": API_KEY,
      "anthropic-version": "2023-06-01",
      "content-type": "application/json",
    },
    body: JSON.stringify({
      model: MODEL,
      max_tokens: 700,
      temperature: 0.4,
      system: SYSTEM,
      messages: [{ role: "user", content: `Week data:\n${payload}` }],
    }),
  });
  if (!res.ok) {
    console.error("Anthropic error", res.status, await res.text());
    return json({ message: "The summary service is busy. Try again later." }, 502);
  }
  const data = await res.json();
  const text: string = (data.content ?? []).filter((b: { type: string }) => b.type === "text").map((b: { text: string }) => b.text).join("");
  const start = text.indexOf("{");
  const end = text.lastIndexOf("}");
  if (start < 0 || end <= start) return json({ message: "Couldn't read the summary" }, 502);

  try {
    const parsed = JSON.parse(text.slice(start, end + 1));
    const headline = String(parsed.headline ?? "").trim().slice(0, 120);
    const summary = String(parsed.summary ?? "").trim().slice(0, 1200);
    if (!headline || !summary) return json({ message: "Empty summary" }, 502);
    return json({
      headline,
      summary,
      highlights: clean(parsed.highlights, 3),
      watch_items: clean(parsed.watch_items, 3),
      vet_questions: clean(parsed.vet_questions, 3),
    });
  } catch {
    return json({ message: "Couldn't read the summary" }, 502);
  }
});
