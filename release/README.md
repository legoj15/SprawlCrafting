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
  `<loader>/build/libs/sprawlcrafting-<ver>+mc1.16.5-<loader>.jar`). Per loader: `dependencies` names its store
  deps explicitly, and `extraLoaders: false` opts a loader out of the config's extra loaders (set on
  1.16.5-fabric: Quilt never supported 1.16.5). A tree's Gradle tasks run once per tree, not once per loader.

Each store upload is **per-node** and tags its own loader. The update manifest is keyed by **MC version**
(loader-agnostic), so all loaders of one MC version share a block.

## Safe by default

Without `-Execute` the script is a **dry run**: it computes and prints every title, tag, channel, and
changelog variant, validates the rewritten manifest in memory, and shows the exact `gh` command and
Modrinth/CurseForge request bodies — but writes nothing, commits nothing, uploads nothing, and needs
no tokens. Add `-Execute` to publish for real.

```powershell
# preview the whole release (no tokens needed; needs the jars built, or add -AllowMissingJars)
./release/Invoke-Release.ps1

# build every jar first (the modern nodes, the 1.12.2 Forge jar and the 1.16.5 Forge+Fabric pair), then preview
./release/Invoke-Release.ps1 -Build

# publish for real
./release/Invoke-Release.ps1 -Execute

# just one stage
./release/Invoke-Release.ps1 -Only GitHub -Execute

# resume a partially-published release: only the failed nodes, only the store stages.
# -OnlyNodes takes an MC version (every node on it) or a node name; a value matching nothing fails.
# (The GitHub stage would still try to CREATE the release, so resumes normally keep it store-only.)
./release/Invoke-Release.ps1 -Only Modrinth,CurseForge -OnlyNodes 26.3,26.2-fabric -Execute

# preview a checkout that has nodes this one lacks (a worktree), before its jars are built.
# Version/changelog/manifest/nodes/jars/git come from RepoRoot; config + secrets stay this tool's.
./release/Invoke-Release.ps1 -RepoRoot E:\GitHub\SprawlCrafting\.claude\worktrees\<name> -AllowMissingJars

# publish a held-back node for this run only
./release/Invoke-Release.ps1 -ShipHeld 1.16.5-fabric -Execute
```

Other switches: `-AllowMissingJars` (dry run only, refused with `-Execute`) downgrades a missing jar
to a warning + the path it would have — without it a dry run fails on a missing jar like a real run;
`-PromoteRecommended` advances every non-EOL line's `-recommended` to this prerelease (see below);
`-SkipTests` / `-NextVersion` / `-ModrinthStaging` as before (see `Get-Help ./release/Invoke-Release.ps1`).

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
| **GitHub** | `gh release create <ver>` — tag = `version`, title expanded (e.g. `1.0.0 Release Candidate 4`), body = `changelog.md` verbatim, every **publishing** node's jar attached, `--latest` unless an `ar`/`br` build (then `--prerelease`). |
| **Modrinth** | One version per node, `version_number = <ver>+mc<mc>-<loader>` (unique), `loaders = [<loader>]` plus its extra loaders (fabric nodes also `quilt`). Title includes the loader. **One identical changelog on every version**, the featured-MC nodes uploaded first (see below). |
| **CurseForge** | One file per node, tagged with its MC version, loader (`Fabric`/`NeoForge`), extra loaders (fabric files also `Quilt`), the **environment** (`Client`+`Server`), and the **Java tier** (`Java <n>` from the node's `java_version`). Changelog is per MC version. |
| **PostFlight** | Resets `changelog.md` to `###### Changes since <ver>:` (empty body) and, for a prerelease, bumps `version` to the next prerelease (`rc4`→`rc5`); commits + pushes. A **final** release leaves the version alone unless you pass `-NextVersion 1.0.1`. |

Fail-fast, before the slow test suite: an **empty changelog** with any publishing stage selected throws
(Test-only / PostFlight-only warns); `{mc}` tags naming an unknown MC version throw; and when Modrinth or
CurseForge will run, `Test-ScStoreConfig` validates every node's dependency keys and the `extraLoaders`
shape against the config.

## Node scoping and the ship gate

- **`-OnlyNodes <selector[]>`** restricts the Modrinth/CurseForge uploads, and is refused unless `-Only`
  names just those stages (it is the resume path, never a way to cut a partial release). A selector is
  an MC version (`26.3`: all three nodes on it) or a node name (`26.3-fabric`). A selector matching
  nothing fails the run. Changelog-tag validation keeps using the **full** node set.
- **`heldBack`** (`"loaders": [], "nodes": [], "reason": ""` in `config/release.json`) names nodes that
  are enumerated, built, verified and manifest-covered like every other node but are **not published**
  (GitHub/Modrinth/CurseForge) and whose jars are never required (the run reports built/not built).
  Empty today. `-ShipHeld <loader|node>[,...]` lifts it for one run — a value matching no held node
  fails the run. Holding a node of `featuredMc` only warns (that run features nothing on Modrinth).

## Store dependencies, per node

Integrations vary per **node**, not per loader, so `config/release.json` has a top-level
`"dependencies"` catalogue keyed by a short key:

```jsonc
"fabric-api":             { "type": "required", "modrinth": "P7dR8mSH", "curseforge": "fabric-api",
                            "whenProp": "fabric_version", "loaders": ["fabric"] },
"mixinbooter":            { "type": "required", "modrinth": "G1ckZuWK", "curseforge": "mixin-booter" }
```

- A **modern node** advertises every entry whose `whenProp` gradle key its
  `versions/<node>/gradle.properties` pins (e.g. no `jei_version` on the 26.x Forge nodes → no JEI dep)
  and whose `loaders` filter (if any) admits its loader (`modmenu` is Fabric-only). Entries without a
  `whenProp` (`mixinbooter`) are legacy-only.
- A **legacy node** names its keys explicitly: `legacyForge.dependencies` (1.12.2: `jei`, `ae2`,
  `mixinbooter` — the 1.12.2 client hard-stops without MixinBooter) and each `legacyTrees` loader
  entry's `dependencies` (1.16.5-forge: `jei`, `ae2`; 1.16.5-fabric: `fabric-api`, `modmenu`).
- Uploads send Modrinth `dependencies` (`dependency_type` = type) and CurseForge
  `relations.projects` (`requiredDependency` / `optionalDependency`), **required first**; the dry-run
  preview prints e.g. `deps=fabric-api(req), jei(opt), modmenu(opt)`.
- Extra loader tags: `modrinth.extraLoaders` (`{"fabric": ["quilt"]}`) and `curseforge.extraLoaders`
  (`{"fabric": ["Quilt"]}`) add Quilt to every fabric node's loader list / CF game-version tags
  (CurseForge resolves it under the Modloader type like the primary loader). A legacy tree loader
  opts out with `"extraLoaders": false`.

## Version-tagged changelog lines

A bullet written as `- {26.2} Fixed the thing` is **exclusive to that MC version** on the manifest and
CurseForge; Modrinth shows it to every version, labelled:

- **update.json** — the line lands only in the `26.2` block (tag stripped, document order preserved);
  every other block gets the general lines only.
- **CurseForge** — a `26.2` upload shows the general lines + its `{26.2}` lines; a `1.21.1` upload
  shows the general lines only.
- **Modrinth** — every version of every loader gets the **same** full changelog, tagged lines kept
  inline as `- (26.2) Fixed the thing`. The text must be byte-identical across a release: Modrinth's
  changelog tab hides a version's text when an older version on the same page has the exact same
  string, so it prints once per release (the featured-MC nodes upload first to be the ones displaying
  it) while each version page still shows everything.
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

Pass **`-PromoteRecommended`** to force `-recommended` onto a prerelease anyway — for a bugfix-heavy
beta you want players pulled onto now (the in-game update shows as a full "update available" rather
than the muted "beta available"). EOL lines stay pinned; the store channels are unaffected (still
GitHub pre-release / Modrinth+CurseForge beta) — only the in-game recommendation is promoted.

## Testing the CurseForge resolver

`./release/Test-ScCurseForgeResolve.ps1` unit-tests `Select-ScCurseForgeVersionIds` (the pure
name→id mapper) with hand-built CF responses: type-scoped environment tags (the `Server` collision),
the Java-tier soft skip, unknown MC version / loader throws, and the Quilt extra-loader cases
(resolves under the Modloader type; a missing extra loader throws). Token-free, network-free; exits
0 all-pass, 1 any failure. Run it after touching the resolver or the config's loader tags.

## Prerequisites checklist before `-Execute`

- [ ] `./gradlew buildAndCollect` passed → `testing/dist/` has the modern jars, **and** the legacy jars are built: `forge-1.12.2/build/libs/sprawlcrafting-<ver>.jar` and `legacy-1.16.5/{forge,fabric}/build/libs/sprawlcrafting-<ver>+mc1.16.5-<loader>.jar` (or pass `-Build` to do all — the 1.12.2 build needs a JDK 25 host, the 1.16.5 tree JDK 21; set `legacyForge.javaHome` / the `legacyTrees` entry's `javaHome` if your default JDK differs). A missing jar stops the run, dry run included; `-AllowMissingJars` previews without them.
- [ ] `./testing/Invoke-ReleaseTests.ps1` gate is green — **including the `1.12.2-forge` leg**, which boots the reobf jar in a real Forge 1.12.2 + MixinBooter client and verifies both mixin configs registered (this is the only automated check of the jar-manifest `MixinConfigs` wiring; see `testing/README.md`).
- [ ] `changelog.md` finalized for this version (a publishing run with an empty body aborts).
- [ ] On `main`, working tree otherwise clean (the script commits `update.json`, then `changelog.md`+`gradle.properties`).
- [ ] `gh auth status` OK (already is).
- [ ] For a store: its `enabled` is `true`, a real `projectId` is set, and its token is exported.

## Files

| Path | Purpose |
|---|---|
| `Invoke-Release.ps1` | The orchestrator (stages, dry-run/execute, store gating, node scoping, ship gate). |
| `modules/ScRelease.psm1` | All the logic (version grammar, changelog routing, manifest, store APIs). |
| `config/release.json` | Repo, store project IDs/flags, featured MC version, per-MC game versions, `dependencies` / `heldBack` / `extraLoaders`. **Committed.** |
| `config/secrets.ps1` | Your store tokens. **Gitignored** — copy from `secrets.ps1.example`. |
| `Test-ScCurseForgeResolve.ps1` | Offline unit tests for the CF game-version-id resolver. |
