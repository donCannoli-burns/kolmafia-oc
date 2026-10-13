# Kolmaf-AI bootstrap v0

This fork adds a native **Kolmaf-AI** desktop frame next to the Graphical CLI.

The first slice is intentionally small:

- launch or attach to an OpenCode server bound to `127.0.0.1:4096`;
- create an OpenCode session rooted at the active KoLmafia directory;
- send prompts and display the assistant's text response inside KoLmafia;
- never auto-approve OpenCode permissions;
- never expose a new KoL execution path;
- never call the gCLI from the OpenCode frame.

OpenCode may be installed anywhere on `PATH`. Set `KOLMAF_OPENCODE_BIN` to an
absolute executable path when KoLmafia is launched from a desktop environment
whose `PATH` does not include OpenCode.

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
