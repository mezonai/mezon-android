# Contributing to Mezon Android

This guide describes **who does what**, how we expect **human** and **AI-assisted** changes to land, and where to read before touching the codebase.

---

## Roles

### Maintainers

- Set direction for larger refactors, protocol bumps, and release readiness.
- Review merge requests for correctness, architecture fit, and risk on **chat, realtime, and persistence** paths.
- Keep shared docs ([Architecture](./ARCHITECTURE.md), [MCP](./MCP.md), README) consistent with reality.

### Contributors (engineers)

- Ship **focused** changes: one concern per merge request when practical.
- Follow existing patterns in the same package (controllers, `NotificationCenter`, `BaseFragment`, `BaseCell`, Room).
- Run a **debug build** (and relevant tests) before requesting review; note gaps if the environment is incomplete (e.g. missing `google-services.json`).
- Do **not** commit secrets, `local.properties`, or generated paths that are machine-specific. See [README.MD](../README.MD) for Firebase and `mezon.secrets.properties`.

## Suggested workflow

1. **Branch** from the integration branch your team uses (e.g. `main` or `develop`).
2. **Implement** with small, reviewable commits and clear messages.
3. **Open a merge request** with:
   - What changed and **why** (plain language).
   - How to test (screens, flows, or `./gradlew` commands).
   - Known limitations (e.g. protocol symlink not updated, feature flag).
4. **Respond to review** with follow-up commits or comments; avoid force-push noise unless your team standard says otherwise.

---

## Build and checks

From the repository root (see [README.MD — Build and test](../README.MD#build-and-test)):

```bash
./gradlew assembleDebug
```

Fix compile and lint issues introduced by your change. If CI differs from local results, note that in the MR.

---

## Documentation and AI artifacts

- User-facing or technical doc updates belong in **`docs/`** when they help the whole team (you may be asked to add a short note when behavior changes).
- **Cursor rules** (`.cursor/rules/`) and **agent skills** (`.claude/skills/`) are optional but should stay **aligned** with [ARCHITECTURE.md](./ARCHITECTURE.md) when you change conventions; avoid duplicating long prose in three places unless necessary.

---

## Getting help

- Architecture and layering: [ARCHITECTURE.md](./ARCHITECTURE.md).
- Local setup and protos: [README.MD](../README.MD).
- Editor MCP (debug/automation): [MCP.md](./MCP.md).

For product or API semantics, follow your team’s channel for Mezon protocol and backend contracts.
