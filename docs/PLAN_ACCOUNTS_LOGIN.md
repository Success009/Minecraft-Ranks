# Plan: Accounts (Register / Login)

Status: BUILT in the client jar and DEPLOYED on the live matchmaking server in soft mode (no
`MCR_REQUIRE_AUTH`): names with an account need a token, names without one stay open, so old clients keep working.
Strict mode is only used on the local test server (port 8010).
Built: login inside the title screen card, fixed Minecraft name, form picks Create account / Sign in from
`/api/account/exists` (case-insensitive), recovery code shown once and never saved on the device, password
min 6, Log out in the profile overlay, token required on gated routes, `/api/match/report` and
`/api/player/register` gated.
Also built: Forgot password popup (step 1 recovery code, step 2 new password, Show/Hide on password boxes),
recovery code of 3 groups of 4 hex characters (48 random bits), reset limit of 5 wrong codes per day counted per
network address and per computer (random id in `mcr_device_id.txt`), `accounts.json` kept at mode 600.
Not built: optional email, bans/pairing, TLS, claiming old names with stats, checking a reported winner against the server's own match record.
We plan from the PLAYER'S side first. Only after the owner likes how it feels do we plan how to build it.

## 1. Motive (in the owner's words, cleaned up)
- Make the experience smooth, easy, and what a player expects. Not sloppy.
- I do not want to force anything. No "do this, do this". Staying on my platform is not the goal.
- If a player does not want to play, they don't have to. If they want to play, they can, and it
  should feel good when they do.
- Asking for too much (email, phone number) scares people off. Nobody makes an account "randomly",
  so the sign-up must be tiny.
- Accounts are mostly for cracked players, where the Minecraft name proves nothing.
- Email is useful later (recovery, two-factor), but it is an optional extra we suggest, never a
  requirement.
- We are in development. We do NOT build everything now (bans, protection, new-player pairing,
  etc.). But the account must be built so those pieces can be added later and connect together,
  instead of being bolted on and breaking each other.

## 2. Why accounts at all
Today the server trusts whatever name a client sends. Anyone can queue, upload a skin or take Elo
as someone else. Bans, friends, protection and fair pairing for new players all need to know who
is who. An account is the base all of them stand on.

## 3. What we ask at registration
| Ask | Required? | Why |
|---|---|---|
| Username | Yes (prefilled with the Minecraft name) | This is who they are in game |
| Password | Yes | Proves it is them |
| Email | NO, not at sign-up | Optional later, for recovery only |
| Phone number | NO, never planned | Too much to ask |
| Anything else | NO | Keep it tiny |

Two fields and one button. That's the whole register screen.

## 4. The player's journey
1. **First time:** the title screen card shows "Playing as <Minecraft name>" and a password box in place
   of Find Match / Queue Settings / Leaderboard. Options, logo and background stay. Singleplayer and
   normal Minecraft are untouched.
2. **Create account:** the name is fixed (never typed). The game asks the server if the name has an
   account (ignoring case). No account -> "Create account". They pick a password (6+ chars), press the
   button, see the recovery code once ("shown once, not saved on this computer"), press "I saved it",
   and are in. No email check, no waiting.
3. **Staying logged in:** TO VERIFY. Whether the session is kept between launches is not confirmed yet.
   Log out is in the profile overlay and returns to the same card.
4. **Playing online:** queue, challenges, friends and leaderboard use the account. While signed out, Find
   Match, Queue Settings, Leaderboard and the profile card are hidden and the card shows the login. No nagging
   beyond that.
5. **Wrong password / new computer:** the same card, in "Sign in" mode because the name already has an
   account. One password box, no typed name, no switch button.
6. **Forgot password:** see section 5.
7. **Log out:** a button on the profile, nothing more.

Rules for the feel:
- Short, friendly error lines ("Wrong password", "That name is taken"), no technical text.
- If the matchmaker is offline, say so plainly; don't show a broken screen.
- Never lock a player out of the game itself, only out of the online features.

## 5. Recovery without forcing email
Goal: a player who forgets their password should have a way back, without us requiring email.
- **Recovery code:** shown once after creating the account ("save this somewhere safe"). The
  player can skip it. If they keep it, they can reset their own password with it.
- **Optional email:** after they have played a bit, ONE gentle offer in the profile:
  "Add an email so you can recover your account". It has a clear Skip. We do not repeat it
  every session.
- **Owner reset:** as a fallback, the server owner can reset a password by command.
Open for you: do you want the recovery code at all, or only optional email + owner reset?

## 6. Built to expand later (not built now)
We do not build these now. We only make sure the account has room for them:
| Future piece | What the account must already have |
|---|---|
| Friends | A permanent internal id, separate from the username (rename-safe) |
| Bans / moderation | A status on the account (active, banned, with reason and end time) |
| Anti-cheat / protection | A place to attach flags and history per account |
| Pairing for new players | Creation date and games-played count, so the matcher can tell someone is new |
| Email / two-factor | An empty optional email field and a verified flag |
| Premium verification badge | An optional "verified" flag |
| Skins, stats | Keyed on the account id, not the typed name |

The point: one account record that every later system reads from, so they link up instead of each
inventing its own idea of "who is this player".

## 7. What exists today (verified by reading code)
- Identity is `Minecraft.getUser().getName()`, used in about 15 client places.
- Accounts live in `matchmaking-server/accounts.py` (salted hash, tokens, recovery code, case-insensitive names).
- Routes under `/api/account/` handle register, login, me, `exists`, `recover` and `recover_check` (rate-limited per IP; recovery also per computer).
- Gated routes need a token for the exact name. Soft mode lets unregistered names through; `MCR_REQUIRE_AUTH=1`
  (strict) requires a token for every name. `/api/match/report` needs the reporter's token and the reporter must be
  the winner or loser. `/api/player/register` needs the token for that name.
- The server still runs plain HTTP (`HTTPServer`, no SSL), so passwords travel unencrypted.
- The skin `token` in `skins.py` is a cache-busting id, not an auth token.
- Known hole: a signed-in player can report a false winner in their own match.
- Live server runs this in soft mode. Strict mode is only on the local test server.

## 8. Technical notes (ON HOLD until the player experience is approved)
Kept short on purpose. We only go here after you like sections 3 to 5.
- Passwords: salted slow hash from Python's standard library (scrypt or PBKDF2). Never store or
  log plain passwords.
- Session: random token saved locally by the client, sent on every request; server checks it
  matches the name in the request. Roll out soft first (accept tokens, still allow old clients),
  then require them.
- Storage: new `accounts.json`, written to a temp file then renamed.
- Transport: plain HTTP exposes passwords. Needs TLS (reverse proxy) or Tailscale-only before
  login is truly safe.
- Existing players' stats must be linked to accounts (who claims an existing name).
- Rate-limit failed logins.

## 9. Questions for you (player-side first)
1. Is "username + password, nothing else" the right register screen for you?
2. Where should "Sign in / Create account" live: on the profile area of the title screen, or its
   own button?
3. Recovery code at register, or only optional email + owner reset?
4. When should the optional "add email" offer appear (after first win, after N games, only in
   profile settings with no popup)?
5. Should the username have to equal the Minecraft name?
6. An existing name that already has stats: first to register gets it, or you approve manually?
   (Technical, can wait.)
