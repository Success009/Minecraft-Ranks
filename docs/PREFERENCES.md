# Owner preferences (read first; keep this file tiny)
DO = wanted. DONT = rejected. One line per rule. No essays.

## Goal
Smooth, easy, how players expect. Never force. Never sloppy.

## Scope
- DO change only what was asked. Hide/replace the minimum.
- DONT remove unrelated UI (logo, background, card) to "make a gate". (rejected)

## Accounts / login
- DO login lives INSIDE the title screen card, under the logo, replacing the buttons.
- DO signed out: hide only account-needing items (Find Match, Queue Settings, Leaderboard, profile card). Options, logo, background, card stay.
- DO username = the player's Minecraft name, fixed, never typed. Shown as plain "Playing as <name>".
- DO form picks itself: no account -> Create account, account exists -> Sign in. Names match ignoring case.
- DONT "I have an account" / "New here" switch button (rejected, redundant).
- DO keep the "New here? Pick a password." line; the spot under it holds "Forgot password? Reset" once an account exists.
- DO reset password is a centered popup: step 1 recovery code only, step 2 new password. Reusing the old password is allowed.
- DO password boxes have a Show/Hide button. DO limit reset to 5 wrong codes per day per computer and per network.
- DO recovery code is 3 groups of 4 (like A1B2-C3D4-E5F6).
- DONT show filler text that states the obvious (for example "Signed in").
- DO recovery code shown once, clear warning, NEVER saved on the device (can be stolen).
- DO password min 6 chars. DONT add other strength rules.
- DO logout inside the profile overlay; returns to the same card.
- DO register = username + password only. Email later, optional, for recovery.
- DO design from player's view first; implement after owner approves.
- DO keep account record ready for bans/protection/pairing (id, status, created, games_played).
- DONT separate blank full-screen login screen (rejected, looked bad).
- DONT small "Sign in" link under profile card (rejected).
- DONT ask email/phone at register. DONT force or nag. DONT build bans/pairing yet.

## UI
- DO profile overlay redesigned (header + stat tiles). Owner still judging look.

## Workflow
- DO back up files before editing (backups_local/<name>/ + RESTORE.sh).
- DO after build: launch via tools/mcl_run.py, ask owner to test.
- DO keep tools project-only; never touch global CLI/launcher.
- DO log owner preferences here.
- DO on a new task: tell the owner a short numbered plan first, then follow it. No plan file needed.
- DONT run build_deploy.sh (git add -A + force-push) without owner OK.
- DONT bloat docs.

## UI feel (added)
- DONT cramped layouts: >=6px between rows, text never near a button edge.
- DONT copy the current standard style as the base; theme will change later, just make it clean.
- DONT show read-only values as input boxes; use plain text.
