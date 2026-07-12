# Mythos — Host

The host plugin. A fork of [FoliaPluginTemplate-Addon-Based-Version](https://github.com/Crew-co/FoliaPluginTemplate-Addon-Based-Version)
with **no mythology in it at all**: it loads addon jars, and that's the whole job.

```
mythos-host/
├── addon-api/   Addon, AddonContext, AddonSchedulers, @Command, CommandContext   (PUBLISHED)
└── plugin/      the loader, the classloaders, the command framework
```

The Greek mythology lives in four other repos:

| Repo | What it is |
|---|---|
| **MythosCore** | the engine: roles, spirits, eras, powers, profiles. Publishes its own API. |
| **EraOfCreation** | story #1 — Chaos, the Primordials, the sickle |
| **Titanomachy** | story #2 — Kronos, the stone, the war |
| *(next)* | one repo per myth, all the way to the Oresteia |

Each is a standalone [FoliaAddonTemplate](https://github.com/Crew-co/FoliaAddonTemplate)
project. This repo never knows they exist.

## Two changes from the template

### 1. Addon classloaders can see their `depends:`

**This is the change that makes the whole project possible.** In the stock template,
addon classloaders are siblings — parent is the host, and no addon can see a class
inside another addon's jar. So no addon could ever publish an API for other addons to
build on, and anything shared had to be shoved into the host's `addon-api`, which
defeats the point of having a host template at all.

`AddonClassLoader` now delegates, in order: **parent (host, Folia, Kotlin stdlib) → its
own jar → the classloaders of the addons named in `depends:`**. The class is defined by
the *dependency's* loader, so there's exactly one copy and `instanceof` works across
addons — the same guarantee the host already gave for its own API. Dependency lookups
reach only into that addon's own jar (`findOwnClass`), never onwards, so they can't loop.

Which means MythosCore can ship `net.crewco.mythos.api.*` in its own jar, and
EraOfCreation can `compileOnly` it and `depends: [ MythosCore ]`, and the host stays
completely generic — reusable for a project that has nothing to do with Greece.

### 2. A service-registry leak, fixed

`HostAddonContext` kept its services in a `companion object` and never removed them on
unload, so after `/addons reload` every service still pointed at an impl loaded by a
dead classloader. Services are now tracked per-addon and dropped in `cleanup()`.

## Build

```bash
./gradlew build              # → plugin/build/libs/Mythos-0.1.0.jar
./gradlew publishApiLocally  # → ~/.m2, so addons can build with no GitHub token
./gradlew :plugin:runFolia   # a test server
```

Install: `Mythos-0.1.0.jar` in `plugins/`, addon jars in `plugins/Mythos/addons/`.
`/addons` lists them, `/addons reload` re-reads them.

## Folia rules (the ones that actually bit)

- **Shared state** (era, role holders) → `schedulers.global`. The one true race — two
  players claiming the last seat of a role on two region threads in the same tick — is
  closed with a `synchronized` check-and-take inside `RoleServiceImpl.claim`.
- **Another player** → `schedulers.entity(target)`. Reading a foreign player's
  `Location` across regions throws; use `getNearbyEntities`, which only ever returns
  entities your region owns.
- Teleports: `teleportAsync`, always. Disk: `schedulers.async`, always.

## Known caveat (inherited)

Addon commands go into Bukkit's command map and are **not** removed on `/addons reload`,
so a reloaded addon re-registers them (newest wins). Fine for dev; restart for production.
