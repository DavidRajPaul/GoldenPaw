// GoldenPaw push sender: delivers one notification_campaigns row to every opted-in device.
//
// Android → Firebase Cloud Messaging HTTP v1 (data-only message; the app draws the notification).
// iOS     → APNs HTTP/2 with a token-based (.p8) key; no Firebase SDK on iOS.
//
// Deploy:   supabase functions deploy send-push --no-verify-jwt
// Secrets:  supabase secrets set \
//             PUSH_ADMIN_TOKEN=<long random string>            # callers must send it as Bearer
//             FCM_SERVICE_ACCOUNT='<service-account JSON>'      # Firebase → Project settings → Service accounts
//             APNS_KEY_ID=ABC123XYZ APNS_TEAM_ID=TEAMID1234 APNS_TOPIC=com.goldenpaw.app \
//             APNS_PRIVATE_KEY="$(cat AuthKey_ABC123XYZ.p8)" APNS_PRODUCTION=false
// (SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY are provided automatically.)
//
// Call:     POST /functions/v1/send-push  {"campaign_id": "<uuid>"}   Authorization: Bearer $PUSH_ADMIN_TOKEN
//           Schedule it with pg_cron + pg_net for campaigns whose send_at has passed (see docs).
//
// Re-running a campaign is safe: devices that already have a delivery row are skipped.

import { createClient } from "https://esm.sh/@supabase/supabase-js@2.45.4";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const ADMIN_TOKEN = Deno.env.get("PUSH_ADMIN_TOKEN");
const BATCH = 500;
const CONCURRENCY = 50;

const db = createClient(SUPABASE_URL, SERVICE_KEY, { auth: { persistSession: false } });

type Target = { install_id: string; platform: string; push_token: string };
type Campaign = {
  id: string;
  category: string;
  title: string;
  body: string;
  open: string;
  offer_id: string | null;
  status: string;
};
type Result = { install_id: string; status: "sent" | "failed" | "invalid_token"; error?: string };

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

// ------------------------------------------------------------------ JWT helpers (WebCrypto)

function b64url(data: ArrayBuffer | Uint8Array | string): string {
  const bytes = typeof data === "string" ? new TextEncoder().encode(data) : new Uint8Array(data);
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function pemToDer(pem: string): ArrayBuffer {
  const body = pem.replace(/-----[^-]+-----/g, "").replace(/\s+/g, "");
  const raw = atob(body);
  const out = new Uint8Array(raw.length);
  for (let i = 0; i < raw.length; i++) out[i] = raw.charCodeAt(i);
  return out.buffer;
}

async function signJwt(header: object, claims: object, key: CryptoKey, alg: AlgorithmIdentifier | EcdsaParams) {
  const input = `${b64url(JSON.stringify(header))}.${b64url(JSON.stringify(claims))}`;
  const sig = await crypto.subtle.sign(alg, key, new TextEncoder().encode(input));
  return `${input}.${b64url(sig)}`;
}

// ------------------------------------------------------------------ FCM (Android)

let fcmToken: { value: string; exp: number } | null = null;
const fcmAccount = (() => {
  const raw = Deno.env.get("FCM_SERVICE_ACCOUNT");
  return raw ? JSON.parse(raw) as { project_id: string; client_email: string; private_key: string } : null;
})();

async function fcmAccessToken(): Promise<string> {
  if (!fcmAccount) throw new Error("FCM_SERVICE_ACCOUNT not set");
  const now = Math.floor(Date.now() / 1000);
  if (fcmToken && fcmToken.exp - 60 > now) return fcmToken.value;
  const key = await crypto.subtle.importKey(
    "pkcs8", pemToDer(fcmAccount.private_key), { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"],
  );
  const assertion = await signJwt(
    { alg: "RS256", typ: "JWT" },
    {
      iss: fcmAccount.client_email,
      scope: "https://www.googleapis.com/auth/firebase.messaging",
      aud: "https://oauth2.googleapis.com/token",
      iat: now,
      exp: now + 3600,
    },
    key,
    { name: "RSASSA-PKCS1-v1_5" },
  );
  const res = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion }),
  });
  if (!res.ok) throw new Error(`FCM auth failed: ${res.status} ${await res.text()}`);
  const body = await res.json();
  fcmToken = { value: body.access_token, exp: now + (body.expires_in ?? 3600) };
  return fcmToken.value;
}

async function sendFcm(t: Target, data: Record<string, string>): Promise<Result> {
  const token = await fcmAccessToken();
  const res = await fetch(`https://fcm.googleapis.com/v1/projects/${fcmAccount!.project_id}/messages:send`, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
    // Data-only + high priority: the app shows it, respecting the owner's per-category choice.
    body: JSON.stringify({ message: { token: t.push_token, data, android: { priority: "high" } } }),
  });
  if (res.ok) return { install_id: t.install_id, status: "sent" };
  const text = await res.text();
  const invalid = res.status === 404 || text.includes("UNREGISTERED") || text.includes("INVALID_ARGUMENT");
  return { install_id: t.install_id, status: invalid ? "invalid_token" : "failed", error: `${res.status} ${text.slice(0, 200)}` };
}

// ------------------------------------------------------------------ APNs (iOS)

let apnsJwt: { value: string; iat: number } | null = null;

async function apnsToken(): Promise<string> {
  const keyId = Deno.env.get("APNS_KEY_ID");
  const teamId = Deno.env.get("APNS_TEAM_ID");
  const pem = Deno.env.get("APNS_PRIVATE_KEY");
  if (!keyId || !teamId || !pem) throw new Error("APNs secrets not set");
  const now = Math.floor(Date.now() / 1000);
  // Apple wants the provider token refreshed between 20 and 60 minutes.
  if (apnsJwt && now - apnsJwt.iat < 40 * 60) return apnsJwt.value;
  const key = await crypto.subtle.importKey("pkcs8", pemToDer(pem), { name: "ECDSA", namedCurve: "P-256" }, false, ["sign"]);
  const value = await signJwt({ alg: "ES256", kid: keyId }, { iss: teamId, iat: now }, key, { name: "ECDSA", hash: "SHA-256" });
  apnsJwt = { value, iat: now };
  return value;
}

async function sendApns(t: Target, c: Campaign, data: Record<string, string>): Promise<Result> {
  const host = Deno.env.get("APNS_PRODUCTION") === "true" ? "api.push.apple.com" : "api.sandbox.push.apple.com";
  const topic = Deno.env.get("APNS_TOPIC") ?? "com.goldenpaw.app";
  const res = await fetch(`https://${host}/3/device/${t.push_token}`, {
    method: "POST",
    headers: {
      authorization: `bearer ${await apnsToken()}`,
      "apns-topic": topic,
      "apns-push-type": "alert",
      "apns-priority": c.category === "account" ? "10" : "5",
      "apns-collapse-id": c.id.slice(0, 64),
      "content-type": "application/json",
    },
    body: JSON.stringify({
      aps: { alert: { title: c.title, body: c.body }, sound: c.category === "account" ? "default" : undefined, "thread-id": c.category },
      ...data,
    }),
  });
  if (res.ok) return { install_id: t.install_id, status: "sent" };
  const text = await res.text();
  const invalid = res.status === 410 || text.includes("BadDeviceToken") || text.includes("Unregistered");
  return { install_id: t.install_id, status: invalid ? "invalid_token" : "failed", error: `${res.status} ${text.slice(0, 200)}` };
}

// ------------------------------------------------------------------ main

async function pool<T, R>(items: T[], limit: number, fn: (t: T) => Promise<R>): Promise<R[]> {
  const out: R[] = new Array(items.length);
  let next = 0;
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, async () => {
    while (next < items.length) {
      const i = next++;
      out[i] = await fn(items[i]);
    }
  }));
  return out;
}

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ message: "POST only" }, 405);
  if (!ADMIN_TOKEN || req.headers.get("authorization") !== `Bearer ${ADMIN_TOKEN}`) return json({ message: "Forbidden" }, 403);

  let campaignId: string;
  try {
    campaignId = (await req.json()).campaign_id;
  } catch {
    return json({ message: "Invalid JSON" }, 400);
  }
  if (!campaignId) return json({ message: "campaign_id required" }, 400);

  const { data: campaign, error } = await db.from("notification_campaigns").select("*").eq("id", campaignId).single<Campaign>();
  if (error || !campaign) return json({ message: "Unknown campaign" }, 404);
  if (["sent", "cancelled"].includes(campaign.status)) return json({ message: `Campaign is ${campaign.status}` }, 409);

  await db.from("notification_campaigns").update({ status: "sending" }).eq("id", campaignId);

  // Same keys on both platforms; see PushMessage in the app.
  const data: Record<string, string> = {
    category: campaign.category,
    title: campaign.title,
    body: campaign.body,
    open: campaign.offer_id ? "paywall" : campaign.open,
    campaignId: campaign.id,
  };
  if (campaign.offer_id) data.offerId = campaign.offer_id;

  const totals = { sent: 0, failed: 0, invalid_token: 0 };
  try {
    // gp_campaign_targets skips devices that already have a delivery row, so each batch shrinks it.
    for (;;) {
      const { data: targets, error: tErr } = await db.rpc("gp_campaign_targets", { p_campaign: campaignId }).limit(BATCH);
      if (tErr) throw tErr;
      if (!targets || targets.length === 0) break;

      const results = await pool(targets as Target[], CONCURRENCY, async (t) => {
        try {
          if (t.platform === "android") return await sendFcm(t, data);
          if (t.platform === "ios") return await sendApns(t, campaign, data);
          return { install_id: t.install_id, status: "failed", error: "unsupported platform" } as Result;
        } catch (e) {
          return { install_id: t.install_id, status: "failed", error: String(e).slice(0, 200) } as Result;
        }
      });

      for (const r of results) totals[r.status]++;
      await db.from("notification_deliveries").upsert(
        results.map((r) => ({ campaign_id: campaignId, install_id: r.install_id, status: r.status, error: r.error ?? null })),
        { onConflict: "campaign_id,install_id", ignoreDuplicates: true },
      );
      const dead = results.filter((r) => r.status === "invalid_token").map((r) => r.install_id);
      if (dead.length) await db.from("devices").update({ token_status: "invalid" }).in("install_id", dead);
      if (targets.length < BATCH) break;
    }
    await db.from("notification_campaigns")
      .update({ status: "sent", sent_at: new Date().toISOString(), stats: totals })
      .eq("id", campaignId);
    return json({ campaign_id: campaignId, ...totals });
  } catch (e) {
    await db.from("notification_campaigns").update({ status: "failed", stats: { ...totals, error: String(e) } }).eq("id", campaignId);
    return json({ message: String(e), ...totals }, 500);
  }
});
