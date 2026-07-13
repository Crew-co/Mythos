# Mythos

**The engine.** Roles, spirits, eras, powers, profiles — and not one line of Greek
mythology. Chaos, Kronos and Achilles are addons, and they are the only things that are.

Built on [FoliaPluginTemplate-Addon-Based-Version](https://github.com/Crew-co/FoliaPluginTemplate-Addon-Based-Version).

```
addon-api/          ← the ONE artifact a story addon compiles against  (published)
├── addon/          Addon, AddonContext, AddonSchedulers
├── command/        @Command, CommandContext
├── menu/           chest-GUI framework
├── hud/            boss bars, sidebar, action bar
└── api/            Mythos, RoleService, SpiritService, EraService, PowerService,
                    ProfileService, and every event the stories hang off

plugin/
├── addon/          the loader and the classloaders
├── menu/ hud/      the platform implementations
└── engine/         THE ENGINE — RoleServiceImpl, SpiritServiceImpl, EraServiceImpl,
                    PowerServiceImpl, ProfileServiceImpl, the Altar, the HUD, and
                    /claim /roles /role /spirit /era /power /mythos
```

Boot order in `MythosPlugin.onEnable()`: **platform → engine → addons.** By the time a
story addon's `onEnable` runs, `Mythos.from(context)` already resolves — so a story
addon declares **no `depends:` at all** and has exactly one dependency:

```kotlin
compileOnly("net.crewco:mythos-addon-api:0.1.0")
```

## The engine

**Roles.** A definition (`maxHolders`, `ClaimRule`s, powers, tier, succession,
endurance) registered by a story addon. The engine owns who holds it, persists that,
and hands it on when it falls vacant.

**Spirits.** Everyone without a name: invulnerable, flying, unable to touch the world —
and *in the queue*, earning essence for haunting it. When a mantle falls vacant the
engine doesn't shout "first come first served": it **offers** it to the front of the
queue for 60 seconds. Decline and it rolls to the next spirit.

**Never advertise a mantle you'll refuse.** `openRoles()` (has a free seat) and
`claimableBy(player)` (you could actually take it) are different questions, and only the
second one is ever shown to a player. A Titan always has a free seat and *no player may
ever claim one* — they're born, not claimed. The Altar shows those greyed out, with the
real reason, which teaches the rules better than a wiki ever would.

**Eras.** One chapter, with objectives. When the last required one is struck, the engine
advances the world to whatever era declared itself `next` — usually registered by an
addon this one has never heard of — and fires `EraAdvancedEvent`.

**Retirement.** `Endurance.ERA` roles (the sworn armies, the one-scene cast) are
dissolved back into the spirit world when their age ends, with an epithet ("Once Nyx")
and essence that puts them near the front of the queue for the next story.
`Endurance.ETERNAL` roles are untouched — Gaia is still the ground under Troy.
`RoleRetiringEvent` is **cancellable**, which is how one myth inherits a character from
another: the Odyssey keeps Odysseus when the Iliad ends, without either addon importing
the other.

**The Narrator.** `mythos.narrator.tell(beats)` — timed lines, title cards, sounds,
blackouts. Era transitions run through it: the old era's epilogue plays, the world goes
dark and *holds still* (nothing can be claimed), then the next era's prologue begins. A
story gets to finish before the next one starts.

**The Chronicle.** `/chronicle`. Every claim, death, objective and turning of an age,
written as it happens and kept forever — plus whatever a story addon thinks is worth
remembering. Nobody writes this file; it accumulates. It is the cheapest worldbuilding
in the plugin.

**Extensions.** `mythos.extensions` — a noticeboard where one addon opens a point and
any other addon posts to it. **Load order does not matter**: `consume` replays every
contribution already made and receives every one made after. Plus `eras.insertAfter`
(splice a chapter into the chain; the pointers re-link and neither neighbour knows) and
`roles.extend` (give another addon's god a new power).

**Death.** `DivineDeathEvent` fires with the blow *pre-cancelled* when the tiers say a
kill is impossible — and a story addon can un-cancel it. That's how "only the adamantine
sickle can unmake Uranus" lives in EraOfCreation instead of being hard-coded here, and
it's the same hook as Achilles' heel, ten addons later.

## config.yml (`plugins/Mythos/config.yml`)

The settings that define your server:

```yaml
death:
  on-divine-death: RELEASE          # permadeath: killed = mantle torn away
claiming:
  cooldown-seconds: 30              # the rank and file die constantly
  default-role: "olympian-sworn"    # roleless players land HERE, not the spirit world
eras:
  retire-cast-on-advance: true      # the cast of a finished story goes home
```

`default-role` is what makes 100+ players work: being nobody should be a state *between*
roles, not the default condition of ninety people. Leave it empty during the Age of
Chaos — there's nothing to be yet, and that's the point of the Age of Chaos.

## Three changes to the template

**1. The host is a platform, not a classloader.** `context.menus` (a chest-GUI
framework — one holder, one listener, server-wide, and menus belonging to an unloaded
addon are closed before anyone can click a lambda with a dead classloader behind it) and
`context.hud` (keyed boss bars, so two addons can't stomp each other; a sidebar; an
action bar; all safe from any thread). The engine spends both immediately: `/claim`
opens the Altar of Fate, and the age sits in a boss bar over everyone's head.

**2. Addon classloaders can see their `depends:`.** Parent → own jar → the classloaders
of the addons named in `depends:`, with the class defined by the *dependency's* loader
so there's exactly one copy. Not needed to reach the engine (the engine is the plugin) —
needed the moment one *story* wants to extend another, e.g. a `LabourService` that lets
a later jar add a thirteenth Labour of Heracles.

**3. A service-registry leak, fixed.** Services were kept in a `companion object` and
never removed on unload, so `/addons reload` left every one pointing at an impl loaded
by a dead classloader.

`ADDON_API_VERSION` is **2**; the loader refuses a mismatch rather than letting it fail
later with a `NoSuchMethodError`.

## Build

```bash
./gradlew build              # → plugin/build/libs/Mythos-0.1.0.jar
./gradlew publishApiLocally  # → ~/.m2, so addons build with no GitHub token
./gradlew :plugin:runFolia
```

Install: `Mythos-0.1.0.jar` in `plugins/`, story jars in `plugins/Mythos/addons/`.

## Folia rules (the ones that actually bit)

- **Shared state** (eras, role holders) → `schedulers.global`. The one true race — two
  players claiming the last seat on two region threads in the same tick — is closed with
  a `synchronized` check-and-take in `RoleServiceImpl.claim`.
- **Another player** → `schedulers.entity(target)`. Reading a foreign player's
  `Location` across regions throws; use `getNearbyEntities`, which only returns entities
  your region owns.
- Teleports: `teleportAsync`. Disk: `schedulers.async`. Always.

## Caveat (inherited)

Addon commands aren't removed from Bukkit's command map on `/addons reload`. Fine for
dev; restart for production.
