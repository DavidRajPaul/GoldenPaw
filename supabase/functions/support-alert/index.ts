// Emails the developer when someone sends a support request from the app.
//
// Called by the support_requests insert trigger (migration 0006) through pg_net, with the same
// admin token as send-push. Replying to the email goes straight to the owner when they left an
// address (Reply-To), so most tickets are handled from your inbox.
//
// Deploy:   supabase functions deploy support-alert --no-verify-jwt
// Secrets:  supabase secrets set RESEND_API_KEY=re_... SUPPORT_NOTIFY_EMAIL=you@example.com
//           Optional SUPPORT_ALERT_FROM="GoldenPaw <alerts@yourdomain>". Until your domain is
//           verified in Resend, the default sender onboarding@resend.dev can only email the address
//           you signed up to Resend with, so use that address as SUPPORT_NOTIFY_EMAIL.
// (SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY and PUSH_ADMIN_TOKEN are shared with the other functions.)

import { createClient } from "https://esm.sh/@supabase/supabase-js@2.45.4";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const ADMIN_TOKEN = Deno.env.get("PUSH_ADMIN_TOKEN");
const RESEND_API_KEY = Deno.env.get("RESEND_API_KEY");
const NOTIFY = Deno.env.get("SUPPORT_NOTIFY_EMAIL");
const FROM = Deno.env.get("SUPPORT_ALERT_FROM") ?? "GoldenPaw Support <onboarding@resend.dev>";

const db = createClient(SUPABASE_URL, SERVICE_KEY, { auth: { persistSession: false } });

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

const escape = (s: string) =>
  s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "POST only" }, 405);
  if (!ADMIN_TOKEN || req.headers.get("Authorization") !== `Bearer ${ADMIN_TOKEN}`) {
    return json({ error: "unauthorized" }, 401);
  }
  if (!RESEND_API_KEY || !NOTIFY) return json({ skipped: "RESEND_API_KEY or SUPPORT_NOTIFY_EMAIL not set" });

  const { id } = await req.json().catch(() => ({ id: null }));
  if (typeof id !== "number") return json({ error: "id required" }, 400);

  const { data: t, error } = await db
    .from("support_requests")
    .select("id, created_at, topic, message, contact_email, diagnostics, install_id")
    .eq("id", id)
    .single();
  if (error || !t) return json({ error: error?.message ?? "not found" }, 404);

  const d = (t.diagnostics ?? {}) as Record<string, unknown>;
  const supportId = String(d.support_id ?? "");
  const facts = [
    ["Ticket", `#${t.id}`],
    ["Topic", t.topic],
    ["Support ID", supportId],
    ["Reply to", t.contact_email ?? "(no email: answer in a future update or FAQ)"],
    ["App", `${d.app_version ?? "?"} (${d.app_build ?? "?"}) on ${d.platform ?? "?"}`],
    ["Plus", d.plus ? "yes" : "no"],
    ["Signed in", d.signed_in ? "yes" : "no"],
    ["Reminders OK", d.reminders_ok ? "yes" : "no"],
    ["Locale / time zone", `${d.locale ?? "?"} / ${d.timezone ?? "?"}`],
    ["Received", new Date(t.created_at).toISOString()],
  ];
  const closeSql = `update support_requests set status = 'closed', answered_at = now() where id = ${t.id};`;

  const html = `
    <p style="white-space:pre-wrap;font-size:15px">${escape(t.message)}</p>
    <table cellpadding="4" style="border-collapse:collapse;font-size:13px;color:#444">
      ${facts.map(([k, v]) => `<tr><td><b>${escape(k)}</b></td><td>${escape(String(v))}</td></tr>`).join("")}
    </table>
    <p style="font-size:12px;color:#777">When done, run in the SQL editor:<br><code>${escape(closeSql)}</code></p>`;
  const text = `${t.message}\n\n${facts.map(([k, v]) => `${k}: ${v}`).join("\n")}\n\nClose: ${closeSql}`;

  const res = await fetch("https://api.resend.com/emails", {
    method: "POST",
    headers: { Authorization: `Bearer ${RESEND_API_KEY}`, "Content-Type": "application/json" },
    body: JSON.stringify({
      from: FROM,
      to: [NOTIFY],
      subject: `GoldenPaw #${t.id} [${t.topic}] ${supportId}`.trim(),
      html,
      text,
      ...(t.contact_email ? { reply_to: t.contact_email } : {}),
    }),
  });
  if (!res.ok) return json({ error: `Resend ${res.status}: ${(await res.text()).slice(0, 200)}` }, 502);
  return json({ sent: true });
});
