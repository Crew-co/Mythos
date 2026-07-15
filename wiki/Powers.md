# Powers

## A power is what a role lets you do

```kotlin
class SicklePower(private val mythos: Mythos) : Power {
    override val id = "sickle"
    override val displayName = "Forge the Adamantine Sickle"
    override val description = "Grey adamant, jagged-toothed. /power sickle"
    override val cooldownSeconds = 0

    override fun use(ctx: PowerContext): Boolean {
        if (!mythos.eras.isComplete(ERA, "the_imprisonment")) {
            ctx.player.sendMessage(mm("<red>You have no reason yet. Wait — he will give you one."))
            return false        // refused: no cooldown is burned
        }
        ...
        return true
    }
}
```

> **Register the id in a role's `powers` list, or it does not exist.**

## What a power should be

The good ones in this project are almost never damage. They are **levers one player pulls on another
player's story**:

| | |
|---|---|
| `/power birth` | Gaia reaches into the spirit world and makes a waiting player *real* |
| `/power devour` | Kronos sends an Olympian into a **world** that is his stomach |
| `/power madness` | Hera takes another player's mind for ninety seconds. Whatever he does in them, he did. |
| `/power permit` | Hades — a *player* — decides whether Heracles may enter. He can say no. |
| `/power endure` | Prometheus knows the secret that would free him. **The button exists so that not pressing it is a decision.** |
| `/power decree` | Zeus forbids the gods to intervene. Cancels `PowerUseEvent` — including powers from addons written years later. |

## Cooldowns

`use()` returning `false` means *refused* — the cooldown is not burned. Return `true` only when
something actually happened.

`powers.setCooldown(uuid, id, seconds)` lets a story impose or wipe one (a boon from Hermes, a curse
from Hera).

## In-world effects

A power that only sends a message is a *notification*. The good ones change the world:

- the thunderbolt burns a blackstone crater you can walk back to a year later
- the Earth-shaker opens fissures that stay open
- the Hundred-Handed throw **actual falling blocks**
- when Zeus takes fire away, every torch and campfire near every mortal actually **goes out**

Anything **temporary** goes through a [Scar](Realms-and-Gateways#terraform--undo-for-the-world).

## Solo testing

Every crowd-sized number goes through the engine:

```kotlin
if (seated >= mythos.dev.threshold(12)) complete(ERA, "the_twelve", ...)
val killsToEnd = mythos.dev.threshold(config.getInt("war.kills-to-end", 200))
```

`/mythos dev` → the number becomes 1. Hard-code `12` and nobody will ever finish your chapter alone.
