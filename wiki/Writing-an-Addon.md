# Writing an Addon

## 1. Clone the template

[FoliaAddonTemplate](https://github.com/Crew-co/FoliaAddonTemplate). Set `addonName` and `hostRepo` in
`gradle.properties`.

```kotlin
// build.gradle.kts — the ONLY dependency
compileOnly("net.crewco:mythos-addon-api:0.1.0")
```

```yaml
# addon.yml
name: TrojanWar
main: net.crewco.mythos.troy.TrojanWarAddon
api-version: 2
# no depends: — the engine is the plugin, not an addon
```

## 2. Five things, in order

```kotlin
class TrojanWarAddon : AddonBase() {
    override fun onEnable() {
        val mythos = Mythos.from(context)          // 1. grab the engine

        mythos.eras.register(TROJAN_WAR)           // 2. an era — naming the era that FOLLOWS it
        mythos.roles.register(ACHILLES)            // 3. the roles it introduces
        mythos.powers.register(RagePower())        // 4. the powers those roles grant
        context.registerListener(WarListener())    // 5. one listener that turns player
    }                                              //    actions into completed objectives
}
```

That's the whole shape. When your last required objective is struck, the engine advances the world to
`next` — and your addon goes quiet forever, without ever learning what came after it.

## 3. A power

```kotlin
class RagePower(private val mythos: Mythos) : Power {
    override val id = "rage"
    override val displayName = "Sulk"
    override val description = "Withdraw. Everyone will notice. /power rage"
    override val cooldownSeconds = 0

    override fun use(ctx: PowerContext): Boolean {
        val him = ctx.player
        ...
        return true    // false = refuse, and no cooldown is burned
    }
}
```

> **Add the id to a role's `powers` list.** `PowerService` checks it before firing. A power that isn't
> in one is dead code — this is the most common bug in the project and I have shipped it twice.

## 4. Make it land

Don't broadcast. **Stage it.**

```kotlin
mythos.narrator.tell(beats {
    title("<red>The Wrath", "<gray>of Achilles, son of Peleus", sound = "minecraft:entity.wither.spawn")
    pause(60)
    line("<gray>He goes to his tent. He does not come out.")
    line("<dark_gray><i>Nine years of war, and this is the part they wrote a poem about.")
})
mythos.chronicle.record("story", "<red>Achilles <gray>withdrew from the war, and the Greeks began to lose.")
```

## 5. Clean up after yourself

```kotlin
@EventHandler
fun onReset(event: MythosResetEvent) {
    if (event.scope == MythosResetEvent.Scope.PLAYER) return
    state.clear()
    mythos.terraform.heal("troy:walls")
}
```

The engine can only reset what the engine owns. It has no idea you keep a kill tally in `war.yml`.

## Checklist

- [ ] every power id appears in some role's `powers` list
- [ ] crowd-sized numbers go through `mythos.dev.threshold(n)` — you will be testing this alone
- [ ] anything you break in the world goes through a **Scar**
- [ ] you listen for `MythosResetEvent`
- [ ] nothing hard-codes a role or realm belonging to another jar — ask `roles.defaultRole()`
- [ ] cross-region work uses `schedulers.entity(target)` — see [Folia Notes](Folia-Notes)
