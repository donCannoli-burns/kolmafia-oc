# Kolmaf-AI bootstrap v0

This fork adds a native **Kolmaf-AI** desktop frame next to the Graphical CLI.

The bootstrap is intentionally small:

- invoke the installed OpenCode CLI locally with `opencode run --format json`;
- keep OpenCode's JSONL session ID so conversations can continue across prompts;
- expose two explicit primary agents: **Plan** and **Build**;
- default the desktop selector to **Plan**;
- never auto-approve OpenCode permissions;
- never expose a new KoL execution path;
- never call the gCLI from the OpenCode frame.

OpenCode may be installed anywhere on `PATH`. Set `KOLMAF_OPENCODE_BIN` to an
absolute executable path when KoLmafia is launched from a desktop environment
whose `PATH` does not include OpenCode.

Kolmaf-AI passes an explicit `--dir` to OpenCode so repo-local agents are
discovered deterministically. The default workspace is the JVM current working
directory (the source repository during `./gradlew run`). Set
`KOLMAF_OPENCODE_WORKSPACE` to an absolute directory to choose a different
workspace. This workspace selection does not grant access to external
directories.

OpenCode agent runs have a 30-minute hard timeout by default rather than the
bootstrap's original two-minute ceiling. Set `KOLMAF_OPENCODE_TIMEOUT_MINUTES`
to a non-negative integer to override it. A value of `0` disables the hard
timeout so the child process may run until OpenCode exits on its own.

## Agent modes

The repo-local agents live under `.opencode/agents/`.

### `kolmaf-ai-plan`

Plan is the default primary agent. It may inspect, reason, and run a narrow set
of verification commands, but it denies workspace edits and denies external
directories.

### `kolmaf-ai-build`

Build may edit files inside the active OpenCode workspace and run normal
development shell commands there. It still denies external directories and does
not gain live KoL or gCLI authority. Destructive or remote Git operations,
`sudo`, broad recursive deletion, and GitHub CLI commands remain explicitly
denied.

Plan and Build maintain separate OpenCode session IDs. Switching modes therefore
does not silently reuse the other mode's conversation.

The key boundary is:

```text
Plan  = inspect / reason / propose
Build = modify the OpenCode workspace

Neither mode = live KoL authority
```

Any future live KoL capability must be introduced separately through an
explicit governed adapter with state binding, approval, and read-back
verification.

## Intended next seam

This frame is the host, not the intelligence layer. The planned runtime composes
the already-installable KoLmafia capabilities:

1. `kol-agent-sandbox`
2. `tokens-of-loathing`
3. `kol-agent-memory`
4. `kolmafia-skills`
5. `kol-ash-it-down`
6. `kol-goblin-docs`
7. `kol-html-matrix`
8. `kingdomsitter`
9. `actor-engine`

The next vertical slice should discover installed capabilities through the
KoLmafia-local runtime/data plane, then project that discovery into OpenCode.
Availability remains observation, never execution permission.

The later live-evidence path should reuse KoLmafia's existing files rather than
inventing a second state authority:

```text
sessions/*.txt + settings/*_prefs.txt
             |
             v
incremental local listener
             |
             v
normalized evidence
             |
             +--> Goblin Docs
             +--> HTML Matrix
             +--> MM Manager / OpenCode
```

Any future OpenCode -> KoL action path should route through an explicit guarded
capability boundary and preserve KoLmafia's existing command serialization and
read-back verification.
