#!/usr/bin/env bash
# GoldenPaw: one-shot Supabase setup. Safe to re-run any time (e.g. after adding keys later).
#
#   ./scripts/setup-supabase.sh
#
# Does: link the project, apply all migrations, generate + upload function secrets, deploy every
# Edge Function, write supabase.url / supabase.anonKey into local.properties, optionally set the
# GitHub secrets, and print the two remaining dashboard steps (Vault SQL + email template).
#
# Needs: the Supabase CLI (logged in: `supabase login`), openssl, python3. Optional: gh (GitHub CLI).
# Works on macOS, Linux, and Windows via Git Bash or WSL.

set -euo pipefail
cd "$(dirname "$0")/.."

bold() { printf '\n\033[1m%s\033[0m\n' "$*"; }
need() { command -v "$1" >/dev/null 2>&1 || { echo "Missing '$1'. $2"; exit 1; }; }

need supabase "Install it: https://supabase.com/docs/guides/local-development/cli/getting-started (then run: supabase login)"
need openssl "Install OpenSSL (Git Bash on Windows already has it)."
need python3 "Install Python 3 (used to read JSON)."

# ---------------------------------------------------------------- 1. link + migrations
bold "1/6  Link your Supabase project"
REF="${SUPABASE_PROJECT_REF:-}"
if [ -z "$REF" ] && [ -f supabase/.temp/project-ref ]; then REF="$(cat supabase/.temp/project-ref)"; fi
if [ -z "$REF" ]; then
  read -rp "Project ref (Dashboard → Project Settings → General, e.g. abcdefghijklmnopqrst): " REF
fi
supabase link --project-ref "$REF"

bold "2/6  Apply database migrations (0001 … latest)"
supabase db push

# ---------------------------------------------------------------- 2. secrets
bold "3/6  Function secrets"
SECRETS=supabase/secrets.env
if [ ! -f "$SECRETS" ]; then
  cp supabase/secrets.env.example "$SECRETS"
  echo "Created $SECRETS (git-ignored)."
fi
gen_if_blank() {
  local key="$1"
  if grep -qE "^${key}=$" "$SECRETS"; then
    local value; value="$(openssl rand -hex 32)"
    python3 - "$SECRETS" "$key" "$value" <<'PY'
import sys
path, key, value = sys.argv[1:]
lines = open(path).read().splitlines()
lines = [f"{key}={value}" if l == f"{key}=" else l for l in lines]
open(path, "w").write("\n".join(lines) + "\n")
PY
    echo "Generated $key (saved in $SECRETS; also keep it in your password manager)."
  fi
}
gen_if_blank PUSH_ADMIN_TOKEN
gen_if_blank REVENUECAT_WEBHOOK_AUTH

echo
echo "Open $SECRETS and fill in whatever you have now (blank = skipped, add later and re-run)."
read -rp "Press Enter when ready… " _

# Single-line KEY=value entries go up in one batch; *_FILE entries (JSON, .p8 keys) are uploaded one
# by one as KEY="<file contents>", which keeps their quotes and newlines intact.
TMP_ENV="$(mktemp)"; TMP_FILES="$(mktemp)"
trap 'rm -f "$TMP_ENV" "$TMP_FILES"' EXIT
python3 - "$SECRETS" "$TMP_ENV" "$TMP_FILES" <<'PY'
import sys, os, json
src, out_env, out_files = sys.argv[1:]
plain, files = {}, {}
for line in open(src):
    line = line.strip()
    if not line or line.startswith("#") or "=" not in line:
        continue
    k, v = line.split("=", 1)
    v = v.strip().strip('"').strip("'")
    if not v:
        continue
    if k.endswith("_FILE"):
        path = os.path.expanduser(v)
        if not os.path.isfile(path):
            sys.exit(f"{k} points to a missing file: {path}")
        if k == "FCM_SERVICE_ACCOUNT_FILE":
            json.load(open(path))  # fail early on the wrong file
        files[k[:-5]] = path
    else:
        if any(c in v for c in '"\\\n'):
            sys.exit(f"{k}: put values with quotes or backslashes in a file and use {k}_FILE instead")
        plain[k] = v
open(out_env, "w").write("".join(f"{k}={v}\n" for k, v in plain.items()))
open(out_files, "w").write("".join(f"{k}\t{p}\n" for k, p in files.items()))
print("Uploading: " + ", ".join(sorted([*plain, *files])))
PY
if [ -s "$TMP_ENV" ]; then supabase secrets set --env-file "$TMP_ENV"; fi
while IFS=$'\t' read -r key path; do
  [ -n "$key" ] && supabase secrets set "${key}=$(cat "$path")"
done < "$TMP_FILES"

# ---------------------------------------------------------------- 3. functions
bold "4/6  Deploy Edge Functions"
supabase functions deploy weekly-summary
for fn in send-push support-alert revenuecat-webhook; do
  supabase functions deploy "$fn" --no-verify-jwt
done

# ---------------------------------------------------------------- 4. app keys
bold "5/6  App keys (local.properties, and GitHub secrets if you want)"
URL="https://${REF}.supabase.co"
ANON="$(supabase projects api-keys --project-ref "$REF" -o json 2>/dev/null | python3 -c '
import sys, json
try:
    keys = json.load(sys.stdin)
except Exception:
    sys.exit(0)
for k in keys:
    if k.get("name") in ("anon", "publishable") or str(k.get("api_key", "")).startswith("sb_publishable_"):
        print(k.get("api_key", "")); break
' || true)"
if [ -z "$ANON" ]; then
  read -rp "Paste the anon (publishable) key from Project Settings → API Keys: " ANON
fi

python3 - "$URL" "$ANON" <<'PY'
import sys, os
url, anon = sys.argv[1:]
path = "local.properties"
lines = open(path).read().splitlines() if os.path.exists(path) else []
want = {"supabase.url": url, "supabase.anonKey": anon}
seen = set()
for i, l in enumerate(lines):
    k = l.split("=", 1)[0].strip()
    if k in want:
        lines[i] = f"{k}={want[k]}"; seen.add(k)
lines += [f"{k}={v}" for k, v in want.items() if k not in seen]
open(path, "w").write("\n".join(lines) + "\n")
print("local.properties updated (supabase.url, supabase.anonKey).")
PY

if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
  read -rp "Also set GitHub secrets GOLDENPAW_SUPABASE_URL / _ANON_KEY for CI builds? [y/N] " yn
  if [ "${yn:-n}" = "y" ] || [ "${yn:-n}" = "Y" ]; then
    gh secret set GOLDENPAW_SUPABASE_URL --body "$URL"
    gh secret set GOLDENPAW_SUPABASE_ANON_KEY --body "$ANON"
  fi
else
  echo "GitHub CLI not found/logged in: add GOLDENPAW_SUPABASE_URL and GOLDENPAW_SUPABASE_ANON_KEY by hand"
  echo "(GitHub → repo → Settings → Secrets and variables → Actions)."
fi

# ---------------------------------------------------------------- 5. what's left (dashboard only)
TOKEN="$(grep -E '^PUSH_ADMIN_TOKEN=' "$SECRETS" | cut -d= -f2-)"
bold "6/6  Two dashboard steps the CLI can't do"
cat <<EOF

A) SQL editor → https://supabase.com/dashboard/project/${REF}/sql/new → paste and Run:

do \$\$
begin
  if exists (select 1 from vault.secrets where name = 'project_url') then
    perform vault.update_secret((select id from vault.secrets where name = 'project_url'), '${URL}');
  else
    perform vault.create_secret('${URL}', 'project_url');
  end if;
  if exists (select 1 from vault.secrets where name = 'push_admin_token') then
    perform vault.update_secret((select id from vault.secrets where name = 'push_admin_token'), '${TOKEN}');
  else
    perform vault.create_secret('${TOKEN}', 'push_admin_token');
  end if;
end \$\$;

B) Authentication → Emails → Templates: paste supabase/templates/sign-in-code.html into BOTH
   "Magic Link" and "Confirm signup" (subject: Your GoldenPaw code: {{ .Token }}).
   Authentication → Sign In / Providers → Email: keep Email enabled, OTP length 6.

Later, with your domain: Authentication → Emails → SMTP Settings → custom SMTP (Resend), then raise
Authentication → Rate Limits → emails per hour. Until then, sign-in codes only reach the email
addresses of your Supabase organization's members (fine for testing on your own phones).

Done. Re-run this script whenever you add keys to ${SECRETS}.
EOF
