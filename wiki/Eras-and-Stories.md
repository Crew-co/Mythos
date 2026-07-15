# Eras and Stories

## The chain assembles itself

An era declares the id of the one that follows it. Nothing else. The chain is built at bootstrap out
of whatever jars are in the folder.

```kotlin
EraDefinition(
    id = "chaos",
    order = 0,
    next = "titanomachy",     // registered by a jar this one has never heard of
    objectives = listOf(...),
    prologue = beats { ... },
    epilogue = beats { ... },
)
```

When the last **required** objective is struck, the engine advances the world to `next` and fires
`EraAdvancedEvent`. If nothing registered that era, it says so out loud rather than failing quietly.

## Splicing

`next` is a *default*, not a law:

| | |
|---|---|
| `eras.insertAfter(id, era)` | put a chapter in between two others. The pointers re-link. |
| `eras.insertBefore(id, era)` | the same, the other way round |
| `eras.append(era)` | hook onto the end of whatever chain this server actually has |
| `eras.addObjective(id, obj)` | add a beat to **somebody else's** chapter |

`ChthonicRealm` splices `the-abduction` in after `olympian-order`. **OlympianOrder is not edited, is
not told, and would run identically if that jar were deleted tomorrow.**

> **All splices are applied at bootstrap**, not at `onEnable`. Linking during enable meant the shape of
> the story depended on the alphabetical order of the jars in the folder.

## A transition is a scene, not a broadcast

When an era ends:

1. the old era's **epilogue** plays, beat by beat — timed lines, a title card, a sound;
2. the world goes **dark and holds still** for `eras.interlude-ticks` — *nothing can be claimed*;
3. the age turns, `EraAdvancedEvent` fires, the cast of the old story retires;
4. the next era's **prologue** begins.

```kotlin
epilogue = beats {
    title("<dark_red>The Sky Is Cut From The Earth", "<gray>and does not come down again",
          sound = "minecraft:entity.wither.death")
    pause(60)
    line("<gray>Where the blood fell, things grew that nobody wanted:")
    line("<dark_gray><i>the Furies, who remember every oath.")
}
```

Use `mythos.narrator.tell(beats)` for any scene of your own. It schedules each beat as a separate
delayed task — a nine-beat epilogue is nine tiny tasks, not a thread sleeping for twenty seconds while
a region waits on it.

## The Chronicle

`/chronicle` · `/chronicle era chaos`

Every claim, death, objective and turning of an age, written as it happens and kept forever — plus
whatever your story thinks is worth remembering:

```kotlin
mythos.chronicle.record("story",
    "<gray>The sky was cut from the earth with a sickle of grey adamant. " +
    "<dark_gray><i>Gaia made it. One of her children was willing to use it.")
```

**Nobody writes this file. It accumulates.** A player who joins during the Trojan War can read what the
Titans did to each other four ages ago, and who did it, by name. It is the cheapest worldbuilding in
the plugin.

## Objectives

```kotlin
Objective("the_sickle", "Gaia forges a sickle of grey adamant")
Objective("the_unmaking", "Sky is cut from Earth", hidden = true)     // /era won't show it
Objective("first_love", "Eros walks among them", optional = true)     // doesn't block the age
```

`hidden` keeps a twist twisty — and Apollo's `/power oracle` can drag one into the light.

**A required objective genuinely holds the world back.** Which is why a thirteenth Labour of Heracles,
contributed by a jar written years from now, would hold the age open until someone did it.
