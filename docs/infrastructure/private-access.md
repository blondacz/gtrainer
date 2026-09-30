# Single-user access and runtime secrets

Status: authentication/secret code is tested locally; deployed-authentication
verification is pending. No import, stored health records, or model calls yet.

## Access

From this Mac, run `bash scripts/pi-tunnel.sh`, then open
`http://127.0.0.1:8080`. Sign in using the separately provisioned dashboard
password. The public HTML shell and `/healthz` contain no personal data.
Every private `/api/` route requires a valid session, except password login.
Authenticated data routes currently return `feature_not_implemented`.

Authentication uses a salted PBKDF2-HMAC-SHA256 verifier with 600,000 iterations;
the raw dashboard password is not stored on the Pi. Sessions/CSRF tokens are
cryptographically random, memory-only, capped at 32, and expire after eight
hours. Restarting the app invalidates every session. Password checks are globally
limited to ten attempts per ten minutes, including successful logins; retries
after the limit fail with the same generic login error. This is a small
single-user service, not an internet-facing identity provider.

Session cookies are HttpOnly, SameSite=Strict, host-only, and path `/`. All API
responses have `Cache-Control: no-store`. Write requests require the exact
configured Origin and a session-bound CSRF header; login requires the Origin
but not a pre-existing session. The browser keeps the CSRF token only in React
memory, never browser storage, and clears the password input after submission.
No CORS or request-body/access logging is enabled. CSP blocks external scripts,
connections, frames, and inline scripts; framing is also denied.

Cookies default to Secure with HTTPS. The only plaintext exception is the exact
`http://127.0.0.1:8080` origin with explicit `GTRAINER_SSH_TUNNEL_ONLY=true`.
That browser leg stays on Mac loopback; the network leg is encrypted SSH. Do not
visit through `localhost`, change the port, expose the forwarding socket to LAN,
or reuse this exception for LAN HTTP. Local processes able to control this Mac
or the Pi remain trusted. Direct LAN/public routing is still absent; router,
SSH/API-server public reachability, IPv6, and DHCP/failover remain unaudited.

## Provisioning (operator Mac only)

The deployment references the externally provisioned Kubernetes Secret
`gtrainer/gtrainer-runtime`, mounted read-only under `/run/gtrainer-secrets`
with mode 0440 and app GID 10001. Git contains only the Secret name/file paths,
never a Secret payload. No secret values are environment variables or image
inputs. Missing/unreadable/invalid credential configuration fails closed.

Initial setup, using the existing private Intervals.icu key:

```bash
python3 scripts/provision_pi_secrets.py --generate
```

This creates a random dashboard password at
`~/.config/gtrainer/dashboard-password` (mode 600, private directory), sends
only its verifier and the source key over authenticated SSH/stdin, and does not
display either credential. View the password privately on the Mac, or import it
into a password manager; never paste it into chat, Git, issue reports, commands,
or CI. The script refuses to overwrite an existing password file or Pi Secret.
Alternatively, omit `--generate` to reuse a private password file or type a
password interactively without echo. There are no model credentials because
no provider/model has been selected.

Use server-side apply to avoid a duplicate secret payload in a last-applied
annotation. Kubernetes Secret base64 is **not encryption**: Pi root/cluster
administrators can read it, and this change does not claim K3s datastore
encryption at rest. The app receives no cluster service-account token and Flux's
app reconciler has no Secret API permission. CI cannot contact the cluster and
contains no Intervals.icu, dashboard, model, or SSH credentials.

## Rotation and revocation

- Rotate/revoke the Intervals.icu key at the provider, then privately replace
  `~/.intervals/api_key` (mode 600) with the new key. Do not reuse a leaked key.
- For a new dashboard password, use a new private `--password-file` or the
  interactive prompt (a nonexistent `--password-file` selects the prompt).
- Run provisioning with explicit `--rotate`; it will replace the managed Secret
  but will never print old/new values. Then delete **only the exact app pod** to
  reload the verifier and invalidate sessions; never delete its PVC. Secret
  projected files update asynchronously, and the authentication verifier is
  loaded once at startup, so file refresh alone does not revoke existing sessions.
- A password change need not change the Intervals.icu account, imported records,
  or manual events. The source adapter will read the key file at import time.
- Off-Pi backups must not accidentally include unencrypted source keys or the
  Mac password file. Encrypted database backup/restore remains task 2.6.

Tests use only synthetic credentials and cover wrong/missing/fabricated sessions,
expiry/restart/logout, Origin/CSRF denial, bounded bodies/attempts, redacted
objects, UI failures, no browser credential storage, stdin-only provisioning,
private-file permissions, and refusal to provision from CI.

Operator-only live check after the authentication image rolls out:

```bash
python3 scripts/verify_pi_deployment.py
python3 scripts/verify_pi_auth.py
```

The auth check uses the private Mac password file, requests no health records,
checks anonymous/wrong-password/Origin/CSRF rejection and login/logout, and scans
the current pod's last 1,000 log lines in memory for exact credentials/session
tokens without printing log contents. It does not certify every historical log,
provider key validity, or datastore encryption.
