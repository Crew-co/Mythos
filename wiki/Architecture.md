# Architecture

```
Mythos  (one Gradle module)
├── src/main/kotlin/net/crewco/mythos/
│   │   ┌─ the addon API (published as net.crewco:mythos-addon-api) ─────────────┐
│   │   │ api/          Mythos, RoleService, SpiritService, EraService,          │
│   │   │               PowerService, ProfileService, RealmService,              │
│   │   │               TerraformService, NarratorService, ChronicleService,     │
│   │   │               ExtensionService, DevService — and every event           │
│   │   │ addon/        Addon, AddonContext, AddonSchedulers                      │
│   │   │ command/      @Command, CommandContext   ·  menu/ hud/  frameworks      │
│   │   └──────────────────────────────────────────────────────────────────────┘
│   ├── MythosPlugin      the entry point
│   ├── addon/            the loader and the classloaders (host side)
│   ├── menu/  hud/       the platform implementations (host side)
│   ├── engine/           THE ENGINE — every *Impl, the Altar, the HUD, the commands
│   └── scheduler/ util/  Folia schedulers, cooldowns
└── build.gradle.kts      one build: the `Mythos` shaded jar + the `apiJar` slice above

plugins/Mythos/addons/    ← the stories live here
```

The engine and the API share a source tree but not a boundary: the `apiJar` task packages only the
`api`, `addon`, `command`, `hud`, and `menu` packages (minus their host implementations), so an addon
still compiles against a small, stable slice — `compileOnly("net.crewco:mythos-addon-api:…")` — and
never sees the engine.

## Three layers, and the line between them

**The host** loads jars. **The engine** is what Mythos *is*. **The addons** are the mythology.

The line is enforced, and it has been crossed and walked back at least twice:

| The engine provides | The addon decides |
|---|---|
| `realms.openGateway(...)` — proximity, access, rendering | who cuts a gate, where, out of what, what it looks like |
| `terraform.scar(id)` / `heal(id)` | what gets wrecked, and when it goes back |
| `RealmDefinition` — generation, access rules, ambience | that there is a Void, and that it is cold |
| `RoleDefinition` — claiming, succession, retirement | that a Titan is *born*, not claimed |

The engine had the word `"gaia"` in it once — used to bounce anyone refused entry from a realm. A
server running a Norse mythology on this plugin would have been thrown into a wall. It's gone.

## Boot order

```
MythosPlugin.onEnable()
    ├── schedulers, commands
    ├── platform  (menus, hud)
    ├── ENGINE    ← publishes RoleService, SpiritService, EraService... into the service registry
    ├── addons.loadAll()   ← story addons enable here; Mythos.from(context) already resolves
    └── engine.createRealms()   ← worlds can ONLY be created during startup

  ...one tick later:
    └── eras.bootstrap()   ← splices the chain, picks the first era
```

**A story addon declares no `depends:` at all** to reach the engine. It has exactly one dependency:

```kotlin
compileOnly("net.crewco:mythos-addon-api:0.1.0")
```

## Addon classloaders can see their `depends:`

In the stock template, addon classloaders are *siblings* — parent is the host, and no addon can see a
class inside another addon's jar. That makes it impossible for one addon to publish an API for others
to build on.

`AddonClassLoader` delegates in order: **parent (host, Folia, Kotlin stdlib) → its own jar → the
classloaders of the addons named in `depends:`**. The class is defined by the *dependency's* loader,
so there is exactly one copy and `instanceof` works across addons.

Dependency lookups reach only into that addon's own jar (`findOwnClass`) — never onwards — so they
cannot cycle.

This is what makes `LaboursOfHeracles` able to post a `Liberation` into `TheftOfFire`'s extension
point, which is the only hard `depends:` in the entire project.

## The service registry

One map, shared by the host and every addon. The engine publishes into it at startup; addons publish
their own services and those are **removed on unload** — otherwise a `/addons reload` leaves a service
pointing at an impl loaded by a dead classloader.
