# SprawlCrafting release orchestrator

One command to cut a release across **GitHub + Modrinth + CurseForge**, plus the in-game update
manifest (`update.json`) and the post-release changelog/version bump. Mirrors the `testing/` harness
idiom (PowerShell module + JSON config; secrets via env vars).

Everything is derived from a single input — `version` in the root `gradle.properties` — plus the
per-node `versions/<node>/gradle.properties`. You bump `version`, build the jars, run this.

SprawlCrafting ships **one jar per (MC version, loader)**: every `versions/<node>`
of the modern Stonecutter tree (auto-discovered: Fabric / Forge / NeoForge across 1.20.1, 1.21.1, 26.1.2, 26.2
and 26.3), plus the two legacy builds that live outside it. Legacy builds are wired in as
synthetic release nodes through `config/release.json`:

- `legacyForge` — the **1.12.2** Forge jar from `forge-1.12.2/` (host JDK 25, jar `sprawlcrafting-<ver>.jar`).
- `legacyTrees[]` — one entry per extra self-contained tree; today `legacy-1.16.5/` (host JDK 21), which yields a
  **Forge** and a **Fabric** node from one Gradle build (`:forge:build :fabric:build`, jars
  `<loader>/build/libs/sprawlcrafting-<ver>+mc1.16.5-<loader>.jar`). Per loader: `jei` says whether the stores
  list JEI as an optional dependency (Forge only; Fabric 1.16.5 has no JEI). A tree's Gradle tasks run once per
  tree, not once per loader.

Each store upload is **per-node** and tags its own loader. The update manifest is keyed by **MC version**
(loader-agnostic), so all loaders of one MC version share a block.

## Safe by default

Without `-Execute` the script is a **dry run**: it computes and prints every title, tag, channel, and
changelog variant, validates the rewritten manifest in memory, and shows the exact `gh` command and
Modrinth/CurseForge request bodies — but writes nothing, commits nothing, uploads nothing, and needs
no tokens.

```powershell
# preview the whole release (no tokens needed)
./release/Invoke-Release.ps1

# build every jar first (the modern nodes, the 1.12.2 Forge jar and the 1.16.5 Forge+Fabric pair), then preview
./release/Invoke-Release.ps1 -Build

# publish for real
./release/Invoke-Release.ps1 -Execute

# just one stage
./release/Invoke-Release.ps1 -Only GitHub -Execute
```

## Stores are LIVE

`modrinth.enabled` and `curseforge.enabled` in `config/release.json` are **`true`** with real project
IDs (Modrinth `ILhtnhcE`, CurseForge `1575622`, both slug `sprawlcrafting`). 1.0.1 was published to all
three platforms via `-Execute`, so the pipeline is proven end-to-end. **GitHub** works via your existing
`gh auth` (`repo` scope present).

Tokens come from `config/secrets.ps1` (gitignored — copy from `config/secrets.ps1.example`, or export
`$env:MODRINTH_TOKEN` / `$env:CURSEFORGE_TOKEN` yourself). **A dry run needs no tokens** — it previews
every upload without touching the network; only `-Execute` publishes.

> Toggling a store back off: set its `"enabled"` to `false` in `config/release.json`. A dry run still
> **previews** a disabled store (clearly labelled); `-Execute` **skips** it.

Update-check wiring: the **Fabric** check is Mod Menu's default Modrinth-backed checker (automatic once a
version is on Modrinth); **NeoForge** and **Forge 1.12.2** both read `update.json` from the repo via their
native checkers (see the 1.12.2 build's `mcmod.info` `updateJSON` and the NeoForge `updateJSONURL`).

## What it does, in order

| Stage | Action |
|---|---|
| **Manifest** | Adds this version's changelog to every MC block of `update.json` (general lines + that block's `{mc}`-tagged lines, Markdown stripped to plain text), seeds blocks/promos for any new MC line, repoints promos per policy, ASCII-escapes + validates, commits (`Announce <ver> in update manifest`). This commit is what gets tagged. |
| **GitHub** | `gh release create <ver>` — tag = `version`, title expanded (e.g. `1.0.0 Release Candidate 4`), body = `changelog.md` verbatim, **every** node jar attached (modern nodes + the 1.12.2 Forge jar + the 1.16.5 Forge/Fabric jars), `--latest` unless an `ar`/`br` build (then `--prerelease`). |
| **Modrinth** | One version per node, `version_number = <ver>+mc<mc>-<loader>` (unique), `loaders = [<loader>]`. Title includes the loader. |
| **CurseForge** | One file per node, tagged with its MC version, loader (`Fabric`/`NeoForge`), the **environment** (`Client`+`Server`), and the **Java tier** (`Java <n>` from the node's `java_version`). |
| **PostFlight** | Resets `changelog.md` to `###### Changes since <ver>:` (empty body) and, for a prerelease, bumps `version` to the next prerelease (`rc4`→`rc5`); commits + pushes. A **final** release leaves the version alone unless you pass `-NextVersion 1.0.1`. |

## Version-tagged changelog lines

Every node gets the **same** full changelog (all versions are built from one shared source and are
equals — there is no "primary vs backport" framing). The one exception: a bullet written as
`- {26.2} Fixed the thing` is **exclusive to that MC version**:

- **update.json** — the line lands only in the `26.2` block (tag stripped, document order preserved);
  every other block gets the general lines only.
- **Modrinth/CurseForge** — a `26.2` upload shows the general lines + its `{26.2}` lines; a `1.21.1`
  upload shows the general lines only.
- **GitHub** — always gets `changelog.md` verbatim, tags included.

The tag must be a bare dotted version number in **curly braces** followed by a space (square brackets
would collide with a Markdown link at bullet start). A tag naming an MC version that is neither a
manifest block nor a shipping node fails the run up front, dry run included.

## Channel & promo rules (encoded in the module)

| `version` | Title phrase | GitHub | Modrinth | CurseForge |
|---|---|---|---|---|
| `…` (none) | — | latest | release | release |
| `…-rcN` | Release Candidate N | latest | beta | beta |
| `…-hfN` | Hotfix N | latest | release | release |
| `…-brN` | Beta Release N | pre-release | beta | beta |
| `…-arN` | Alpha Release N | pre-release | alpha | alpha |

Manifest promos: `-latest` always advances. `-recommended` advances on a stable build (final/`hf`) or
while still in the initial prerelease run-up, and otherwise stays put once a stable has shipped.
End-of-support lines can be pinned via `endOfSupportLines` in `config/release.json` (empty for now).

## Prerequisites checklist before `-Execute`

- [ ] `./gradlew buildAndCollect` passed → `testing/dist/` has the modern jars, **and** the legacy jars are built: `forge-1.12.2/build/libs/sprawlcrafting-<ver>.jar` and `legacy-1.16.5/{forge,fabric}/build/libs/sprawlcrafting-<ver>+mc1.16.5-<loader>.jar` (or pass `-Build` to do all — the 1.12.2 build needs a JDK 25 host, the 1.16.5 tree JDK 21; set `legacyForge.javaHome` / the `legacyTrees` entry's `javaHome` if your default JDK differs).
- [ ] `./testing/Invoke-ReleaseTests.ps1` gate is green — **including the `1.12.2-forge` leg**, which boots the reobf jar in a real Forge 1.12.2 + MixinBooter client and verifies both mixin configs registered (this is the only automated check of the jar-manifest `MixinConfigs` wiring; see `testing/README.md`).
- [ ] `changelog.md` finalized for this version.
- [ ] On `main`, working tree otherwise clean (the script commits `update.json`, then `changelog.md`+`gradle.properties`).
- [ ] `gh auth status` OK (already is).
- [ ] For a store: its `enabled` is `true`, a real `projectId` is set, and its token is exported.

## Files

| Path | Purpose |
|---|---|
| `Invoke-Release.ps1` | The orchestrator (stages, dry-run/execute, store gating). |
| `modules/ScRelease.psm1` | All the logic (version grammar, changelog routing, manifest, store APIs). |
| `config/release.json` | Repo, store project IDs/flags, featured MC version, per-MC game versions. **Committed.** |
| `config/secrets.ps1` | Your store tokens. **Gitignored** — copy from `secrets.ps1.example`. |
