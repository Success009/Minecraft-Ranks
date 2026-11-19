# MCL Launcher: how it works and how the agent launches the game

Status: `tools/mcl_run.py` is in use. Confirmed from the agent's shell: `launch --restart`, `status`
(finds the game pid), and `log --errors` all work, and the owner saw the game window open.
Not re-checked: `stop` and `crashes`.

PROJECT-ONLY RULE: this tool lives in the project (`tools/`). Do not edit the global `mcl` launcher or
`~/.config/mcl/settings.json` for project work.

## RULE FOR THE AGENT (read this first)
After you finish a feature and the build has placed the jar in the instance's mods folder:
1. Run `python3 tools/mcl_run.py launch --restart`
2. Run `python3 tools/mcl_run.py log --errors` to catch startup problems.
3. **Tell the owner: "Launched, please test"** and say what to test.
4. Wait for the owner's feedback. Do not keep launching in a loop.
You are allowed to launch the game yourself. Never run `mcl mcr` directly: it runs in the
foreground and blocks the shell until the game exits.

## What mcl is
- Owner's own terminal Minecraft launcher (Python package `mcl_launcher`, uses `portablemc`).
- Project: `/home/success0/Documents/BigFish-cli/projects/mcl-launcher`
  (`mcl-launcher-win` is the Windows build, ignore it for this work).
- Installed command: `~/.local/bin/mcl` -> `python3 -m mcl_launcher.cli`.
- Settings: `~/.config/mcl/settings.json` (username, ram, jvm args, `explicitly_installed` list).
- Minecraft dir: `~/.minecraft`. Each version is isolated in `~/.minecraft/instances/<id>`.
  `saves`, `resourcepacks`, `servers.dat` are symlinked to the shared `~/.minecraft` ones.

## The MCR instance
| Item | Value |
|---|---|
| Name in mcl | `mcr` |
| Instance id | `fabric_26.1.2` (Fabric, Minecraft 26.1.2) |
| Folder | `~/.minecraft/instances/fabric_26.1.2` |
| Mods folder | `.../fabric_26.1.2/mods` (`build_deploy.sh` copies the jar here) |
| Log | `.../fabric_26.1.2/logs/latest.log` |
| Crash reports | `.../fabric_26.1.2/crash-reports/` |
Other instances exist (`fabric_26.3` = Century, `fabric_26.2_2`, etc.). Do not touch them.

## mcl command line (from `cli.py`)
- `mcl` interactive menu. `mcl <query>` fuzzy-matches an installed version and launches it in the
  FOREGROUND (blocks). `mcl -u <name> <query>` sets the username for that run.
- Background launch exists only through the menu or `launch_game(..., mode="background")`, which
  ends with `sys.exit(0)` and discards the game's output.
- Launch builds a `portablemc ... start <version> -u <name> -i <offline uuid> --jvm-args ...`
  command. Username comes from `settings.json`; the UUID is the offline UUID of that name.

## The tool: `tools/mcl_run.py`
| Command | What it does |
|---|---|
| `launch [--restart] [--wait SEC] [--matchmaker URL]` | Starts the instance detached via mcl's own `launch_game` in a child process. Refuses if already running unless `--restart`. Errors if no MCR jar is in mods. `--matchmaker URL` points this launch at another matchmaker, e.g. a local test server (`http://127.0.0.1:8010`); the tool prints the URL it uses, and the game's name check showed up in that server's log. How the URL reaches the game was not read. |
| `status` | Running or not, pids, MCR jars in mods (warns on duplicates), log age. |
| `stop` | SIGTERM the game, SIGKILL if it ignores it. |
| `log [-n N] [--errors]` | Tail `latest.log`, or only WARN/ERROR/FATAL/Exception lines. |
| `crashes [-n N]` | Newest crash reports, first 40 lines of the newest. |
Env: `MCL_PROJECT`, `MCL_INSTANCE_ID` override the defaults.

## Typical flow
```
./build_deploy.sh              # builds and copies the jar into the instance mods folder
python3 tools/mcl_run.py launch --restart
python3 tools/mcl_run.py log --errors
# -> tell the owner to test, then act on their feedback
```
If the game dies: `python3 tools/mcl_run.py crashes`.

## Pitfalls
- Keep exactly ONE `mcr-*` jar in mods. `AutoUpdater.initializeObliterator()` deletes other
  `mcr-*`, `nmlpvp-*`, `fabric-bridge-*` jars at startup.
- `build_deploy.sh` can also copy to `~/.minecraft/mods` and a vault path. Only the instance
  folder matters for launching.
- The tool cannot see inside the game window. It only knows: process running, log lines, crashes.
  Whether a feature works is up to the owner's test.
- A launch needs a desktop session (DISPLAY). Not verified from this agent's shell yet.
- Not verified: whether the game process command line contains `instances/fabric_26.1.2`
  (the tool finds it that way). If `status` says "no" while the game is up, fix `game_pids()`.
