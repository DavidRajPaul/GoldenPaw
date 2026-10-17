// Keeps the `subscriptions` table in step with RevenueCat (GoldenPaw Plus).
//
// RevenueCat calls this for every purchase, renewal, cancellation, billing issue, expiry, refund
// and transfer. Rather than trusting each event's details, we re-read the customer from the
// RevenueCat REST API and store the authoritative "plus" entitlement state. Retries are harmless.
//
// Only signed-in owners have a row: the app logs in to RevenueCat with the Supabase user id, so
// app_user_id (or one of its aliases) is that uuid. Anonymous purchases still unlock Plus on the
// device through the RevenueCat SDK; they reach this table once the owner signs in.
//
// Deploy:   supabase functions deploy revenuecat-webhook --no-verify-jwt
// Secrets:  supabase secrets set REVENUECAT_WEBHOOK_AUTH=<random string> REVENUECAT_SECRET_KEY=sk_...
//           RevenueCat → Integrations → Webhooks: URL https://<ref>.supabase.co/functions/v1/revenuecat-webhook,
//           Authorization header value = the same REVENUECAT_WEBHOOK_AUTH string.

import { createClient } from "https://esm.sh/@supabase/supabase-js@2.45.4";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const WEBHOOK_AUTH = Deno.env.get("REVENUECAT_WEBHOOK_AUTH");
const RC_SECRET = Deno.env.get("REVENUECAT_SECRET_KEY");
const ENTITLEMENT = Deno.env.get("REVENUECAT_ENTITLEMENT") ?? "plus";

const db = createClient(SUPABASE_URL, SERVICE_KEY, { auth: { persistSession: false } });

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

type RcEvent = {
  type: string;
  app_user_id?: string;
  original_app_user_id?: string;
  aliases?: string[];
  transferred_from?: string[];
  transferred_to?: string[];
};

type RcSubscription = {
  expires_date: string | null;
  period_type?: string;
  store?: string;
  unsubscribe_detected_at?: string | null;
  billing_issues_detected_at?: string | null;
  refunded_at?: string | null;
  store_transaction_id?: string;
};

type RcSubscriber = {
  entitlements: Record<string, { expires_date: string | null; product_identifier: string }>;
  subscriptions: Record<string, RcSubscription>;
};

async function fetchSubscriber(appUserId: string): Promise<RcSubscriber | null> {
  const res = await fetch(`https://api.revenuecat.com/v1/subscribers/${encodeURIComponent(appUserId)}`, {
    headers: { Authorization: `Bearer ${RC_SECRET}`, "Content-Type": "application/json" },
  });
  if (res.status === 404) return null;
  if (!res.ok) throw new Error(`RevenueCat ${res.status}: ${(await res.text()).slice(0, 200)}`);
  return (await res.json()).subscriber as RcSubscriber;
}

/** "goldenpaw_plus_yearly:yearly" (Play product:base plan) or an App Store product id → pricing_plans.id */
async function planIdFor(productId: string): Promise<string | null> {
  const bare = productId.split(":")[0];
  const { data } = await db
    .from("pricing_plans")
    .select("id")
    .or(`android_product_id.eq.${bare},ios_product_id.eq.${bare}`)
    .limit(1);
  return data?.[0]?.id ?? null;
}

function storeOf(store: string | undefined): string {
  switch (store) {
    case "play_store": return "play";
    case "app_store":
    case "mac_app_store": return "app_store";
    case "promotional": return "promo";
    default: return "manual";
  }
}

async function sync(userId: string): Promise<string> {
  const exists = await db.auth.admin.getUserById(userId);
  if (exists.error || !exists.data?.user) return "no such user";

  const sub = await fetchSubscriber(userId);
  const ent = sub?.entitlements?.[ENTITLEMENT];
  const now = Date.now();

  if (!ent) {
    // Never had Plus (or it was revoked entirely): mark any existing row expired.
    await db.from("subscriptions").update({ status: "expired", auto_renew: false, updated_at: new Date().toISOString() })
      .eq("user_id", userId);
    return "no entitlement";
  }

  const product = ent.product_identifier;
  const s: RcSubscription = sub!.subscriptions?.[product] ?? { expires_date: ent.expires_date };
  const expiresMs = ent.expires_date ? Date.parse(ent.expires_date) : Number.POSITIVE_INFINITY;
  const active = expiresMs > now && !s.refunded_at;

  let status: string;
  if (!active) status = "expired";
  else if (s.billing_issues_detected_at) status = "grace";
  else if (s.unsubscribe_detected_at) status = "cancelled"; // still Plus until current_period_end
  else if (s.period_type === "trial") status = "trial";
  else status = "active";

  const row = {
    user_id: userId,
    plan_id: await planIdFor(product),
    store: storeOf(s.store),
    status,
    original_txn_id: s.store_transaction_id ?? null,
    current_period_end: ent.expires_date,
    auto_renew: active && !s.unsubscribe_detected_at,
    updated_at: new Date().toISOString(),
  };
  const { error } = await db.from("subscriptions").upsert(row, { onConflict: "user_id" });
  if (error) throw new Error(error.message);
  return status;
}

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "POST only" }, 405);
  if (!WEBHOOK_AUTH || req.headers.get("Authorization") !== WEBHOOK_AUTH) return json({ error: "unauthorized" }, 401);
  if (!RC_SECRET) return json({ error: "REVENUECAT_SECRET_KEY not set" }, 500);

  const body = await req.json().catch(() => null);
  const event = body?.event as RcEvent | undefined;
  if (!event) return json({ error: "no event" }, 400);
  if (event.type === "TEST") return json({ ok: true, test: true });

  const ids = new Set(
    [event.app_user_id, event.original_app_user_id, ...(event.aliases ?? []),
     ...(event.transferred_from ?? []), ...(event.transferred_to ?? [])]
      .filter((x): x is string => typeof x === "string" && UUID.test(x)),
  );
  if (ids.size === 0) return json({ ok: true, skipped: "anonymous customer" });

  const results: Record<string, string> = {};
  try {
    for (const id of ids) results[id] = await sync(id);
  } catch (e) {
    // 5xx makes RevenueCat retry later.
    return json({ error: String(e).slice(0, 300) }, 500);
  }
  return json({ ok: true, type: event.type, results });
});
