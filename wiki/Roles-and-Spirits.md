# Roles and Spirits

## A role is a definition

```kotlin
RoleDefinition(
    id = "gaia",
    displayName = "Gaia",
    tier = RoleTier.PRIMORDIAL,
    era = "chaos",
    maxHolders = 1,
    powers = listOf("birth", "sickle"),
    claimRules = listOf(ClaimRules.sinceEra("chaos")),
    succession = Succession.QUEUE,
    endurance = Endurance.ETERNAL,
)
```

The engine owns who holds it, persists that, and hands it on when it falls vacant.

> ⚠️ **A power missing from `powers` is a power that does not exist.** `PowerService` checks this list
> before it fires anything. This is the single most common bug when writing a story.

## Tiers

| Tier | Hearts | Notes |
|---|---|---|
| `PRIMORDIAL` | +40 | Chaos, Gaia, Uranus |
| `TITAN` | +24 | Kronos, Prometheus |
| `OLYMPIAN` | +30 | the Twelve |
| `CHTHONIC` | +20 | Hades, Charon, the Erinyes |
| `MONSTER` | +16 | Medusa, the Cyclopes |
| `DEMIGOD` | +10 | Heracles, Perseus, Medea |
| `HERO` | +6 | Jason, Iolaus, the Argonauts |
| `MORTAL` | 0 | everybody else |

Tiers drive `killableBy` — **gods need gods.** A mortal cannot put down a Titan, whatever the sword.
And they gate the realms: Olympus is `RealmRules.DIVINE`.

## Claim rules

```kotlin
ClaimRules.permission("mythos.claim.zeus")   // the "certain players only" lever
ClaimRules.whitelist(setOf(uuid))            // a hand-picked cast
ClaimRules.minPlaytimeHours(10)
ClaimRules.essenceCost(60)                   // a THRESHOLD, not a price
ClaimRules.sinceEra("chaos")                 // this age, and every age after — the safe default
ClaimRules.duringEra("titanomachy")          // ONLY this age. Seals the role when it ends.
ClaimRules.QUEUE_PRIORITY                    // only the front of the queue
```

**And a rule can deny everyone.** That's how the Titans work: no player may `/claim kronos` — they are
*born*, and the only way in is for whoever holds **Gaia** to reach into the spirit world and pull you
out of it.

## Never advertise a mantle you'll refuse

Two different questions, and only the second is ever shown to a player:

| | |
|---|---|
| `openRoles()` | has a free seat |
| `claimableBy(player)` | **you could actually take it** |
| `evaluate(player, id)` | dry run — gives you the *reason* |

A Titan always has a free seat and no player may ever claim one. `/claim` shows those greyed out,
**with the real reason** — *"Titans are not claimed. They are born, and Gaia has not borne you."* That
locked list teaches the rules better than any wiki page, including this one.

## Spirits

Everyone without a name: invulnerable, flying, unable to touch the world — and **in the queue**,
earning essence for haunting it and for being present when history happens.

When a mantle falls vacant, the engine doesn't shout *first come first served*. It **offers** it to the
front of the queue for 60 seconds. Decline, and it rolls to the next spirit in line.

Multi-seat roles (the sworn armies, the mortals) are never *offered* — there's no scarcity to
arbitrate. Offers exist for the one seat everybody wants.

## `default-role` — the setting that makes 100+ players work

```yaml
claiming:
  default-role: "mortal"
```

Being nobody should be a state **between** roles, not the default condition of ninety people. Point
this at a many-seated role and joiners land there instead of the spirit world.

**And it drives reincarnation:** if the role you just lost *is* the default role, you spend five
seconds as a ghost and get a new body. A mortal dies and a mortal is born. **A god who dies gets
nothing of the kind**, and that asymmetry is the entire point.

> `ChthonicRealm` cancels this (`PlayerReincarnatingEvent`) until you have crossed the river. Which is
> not a mercy — it's Lethe.

## Retirement

`Endurance` decides who survives the turning of an age:

| | |
|---|---|
| `ETERNAL` | untouched. Gaia is still the ground under Troy. **The default.** |
| `ERA` | the cast of one story. When its era ends they go back to the spirit world — with an epithet (*"Once Nyx"*), essence, and a place near the front of the queue for the next chapter. |

**`RoleRetiringEvent` is cancellable**, which is how one myth *inherits* a character from another.

## Essence

The spirit currency, and the payoff for the whole system. Earned by waiting, by witnessing history, by
retiring from a role you played well — and **spent on claiming a bigger part next time.**

Which is why the veterans of the Titanomachy are the ones who can afford a throne in the Olympian
Order. Time served in the story buys a bigger part in the next one.
