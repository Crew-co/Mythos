# Realms and Gateways

## A realm is a world, with rules about who may stand in it

```kotlin
RealmDefinition(
    id = "olympus",
    kind = RealmKind.SKY,          // void + a generated island at platformY
    access = RealmRules.DIVINE,
    refusal = "<gray>You are put back down, gently, and without discussion.",
    ambientSound = "minecraft:block.beacon.ambient",
    ambientParticle = "END_ROD",
    flight = true,
)
```

| Kind | |
|---|---|
| `PRIMARY` | the server's existing overworld. Nobody generates the ground; it was here first. |
| `VOID` | nothing. An empty world with a small platform, for things that aren't yet. |
| `SKY` | void, with a generated island. Olympus. |
| `CAVERN` | solid rock with a hollow band carved out of it, a floor, and **no sky, ever**. Tartarus. The House of Hades. |
| `OVERWORLD` | normal generation |
| `NETHER` / `END` | **require runtime world creation — these do not work on Folia.** Use `CAVERN`. |

`still = true` → no time, no weather, no mobs. **The Void does not have a Tuesday.**

## How worlds actually get made

**Folia will not create a world while the server is running.** `WorldCreator.createWorld()` throws.

So the engine uses the one path Bukkit has always supported: it writes each realm into `bukkit.yml`
and answers `getDefaultWorldGenerator` at world-load — which happens *before* plugins enable, and
therefore before a single addon has declared anything. (The generator settings are cached to
`realms.yml` on each boot for exactly that reason.)

**Practical consequence: the first boot after installing a realm needs a restart.** The engine says
so, loudly, in a box. After that they are ordinary worlds that load with the server.

```yaml
# bukkit.yml — written for you
worlds:
  mythos_void:
    generator: Mythos:void
```

And a design consequence worth having: `bukkit.yml` cannot set a world's *environment*, which is why
Tartarus and the Underworld are `CAVERN`s rather than Nether worlds. Which is better. *It is not fire.
It is a great grey plain, and it goes on.*

## Access is enforced by the world

Olympus is not a build with a warning sign. A mortal who finds a way up is **put back down, by the
world, immediately** — which is the only way "the gods live somewhere you don't" is ever true on a
server where anyone owns a shovel.

```kotlin
RealmRules.OPEN · SENT_ONLY · LIVING · SPIRITS · DIVINE
RealmRules.roles("hades", "charon")
RealmRules.tiers(RoleTier.OLYMPIAN)
RealmRules.flagged("chthonic.permitted")
RealmRules.any(a, b, c)
```

**Being allowed somewhere and being able to get out are different problems.** Tartarus admits the gods
(somebody has to be the jailer) and does not let the imprisoned leave — both true at once. The leash
is a property of the prison, not the door.

## `realms.grant` — a new way into a realm you don't own

```kotlin
mythos.realms.grant("underworld", RealmKeys.bearing(BOUGH))
```

`roles.extend`, but for **places**. ChthonicRealm teaches *OlympianOrder's* underworld to accept a
golden bough it has never heard of. No change, no knowledge, no version bump.

## Gateways: doors you can walk to

`/mythos realm underworld` is an admin command, not a mythology. **The dead do not type.** Heracles did
not type.

```kotlin
mythos.realms.openGateway(Gateway(
    id = "taenarum",
    at = mouth,
    toRealm = "underworld",
    radius = 3.0,
    refusal = "<dark_gray><i>Something looks up at you, and does not let you past.",
    requires = RealmRules.any(RealmRules.LIVING, RealmRules.flagged(FOLLOWING)),
    particle = "SOUL_FIRE_FLAME",
    sound = "minecraft:ambient.soul_sand_valley.mood",
))
```

And it **refuses them** — that's the good half. A mortal can stand at the mouth of the House of Hades,
see the soul-fire coming out of it, and be told no. An entrance nobody can find is a locked door; an
entrance everyone can find and almost nobody can use is a **place**.

`Gateway.requires` is an *extra* rule on the door itself, on top of the destination's. That's how the
way **out** of the Underworld admits only the living — or someone walking out behind a man who hasn't
turned round yet. **That one rule on that one door is the entire Orpheus story.**

Every gate in the project is cut by a **power** (`/power gate`, `/power ascent`, `/power tartarus_gate`)
and every one of them closes (`/power gate close`).

## Item-keys

Not every mythology has a friendly god on hand, and Greek myth mostly didn't either — it had
**objects**. You weren't let in; you *brought something*.

```kotlin
val BOUGH = RealmKeys.key(context.plugin, "golden-bough")
RealmKeys.mark(ItemStack(Material.GOLDEN_CARROT), BOUGH)
mythos.realms.grant("underworld", RealmKeys.bearing(BOUGH))
```

`bearing` (anywhere on them) · `holding` (in hand — more dramatic, easier to lose) · `wearing` (a cap
that makes you nobody).

And because a key is an item, **it can be stolen, dropped, or taken off your corpse by someone who then
has it and you don't.** That is not a flaw. That is three-quarters of the plots in Greek literature.

## Terraform — undo, for the world

The flood raises the sea over a whole server. Demeter kills every crop. The Augean Stables bury a field
in filth.

```kotlin
val scar = mythos.terraform.scar("ages:flood")
scar.set(block, Material.WATER)      // remembers the air that was there
...
mythos.terraform.heal("ages:flood")  // over ticks, on each block's own region
```

Persisted to disk, because **a server that crashes mid-flood must not wake up permanently underwater.**

**The rule:** if your story floods the world, your story can put it back. A thunderbolt crater is a
*decision* (don't open a scar, or call `forget()`). A flood that never recedes is a bug.

`/mythos scars` · `/mythos heal <scar|all>` · a world reset heals everything automatically.
