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

**On Paper**, the engine creates each realm's world during `onEnable` with a plugin generator.
Nothing else is needed: they generate on first boot and load with the server every boot after.

**On Folia**, the Bukkit world API is unimplemented — `WorldCreator.createWorld()` throws
([PaperMC/Folia#134](https://github.com/PaperMC/Folia/issues/134)) — so Mythos hands the loading off
to a world manager instead of reaching into server internals itself. The supported one is the
**[Worlds](https://modrinth.com/project/gBIw3Gvy)** plugin:

- Install Worlds. That's it.
- On enable, Mythos asks Worlds to create each realm's world through its API
  (`WorldsAccess.access().create(...)`), passing `Mythos:<id>` as the generator — which Worlds
  resolves back through Mythos's own `getDefaultWorldGenerator`, so the Void/Olympus/Cavern terrain
  comes out exactly as the realm declared. Worlds does the Folia-safe NMS; Mythos keeps the rules.
- Worlds is a **soft dependency** — Mythos loads and runs fine without it. You just won't get the
  extra realms created for you on Folia.

If you'd rather use a different Folia world manager, load each world yourself with the generator
`Mythos:<id>` (Mythos writes it into `bukkit.yml` for you) and Mythos adopts whatever it finds loaded
under the realm's `worldName`:

```yaml
# bukkit.yml — written for you
worlds:
  mythos_void:
    generator: Mythos:void
```

**Gaia (the overworld) always works** on both platforms; only the extra realms need a manager on
Folia. A realm with no world isn't faked — gateways and `/mythos realm` refuse it cleanly and say
why, and the engine logs exactly what to do.

> The split: Mythos owns the *rules* of a realm (access, ambient, gateways, sending). Actually
> *loading* a world on Folia needs server internals, which is version-fragile and better left to a
> dedicated, maintained plugin. So Mythos asks one to do it rather than reimplementing it.

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
