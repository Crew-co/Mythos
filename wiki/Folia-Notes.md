# Folia Notes

There is no main thread. These are the rules that actually bit while writing nine addons.

## Shared state → `schedulers.global`

Era state, role holders, anything the whole server shares.

**The one true race** — two players claiming the last seat of a role on two region threads in the same
tick — is closed with a `synchronized` check-and-take inside `RoleServiceImpl.claim`.

## Another player → `schedulers.entity(target)`

```kotlin
context.schedulers.entity(target) {
    target.addPotionEffect(...)      // safe: we're on the region that owns them
}
```

## Reading a foreign player's `Location` **throws**

This is the one that will get you.

```kotlin
// ❌ crosses a region boundary
val distance = other.location.distanceSquared(me.location)

// ✅ getNearbyEntities only ever returns entities YOUR region owns
val near = me.getNearbyEntities(20.0, 20.0, 20.0).filterIsInstance<Player>()
```

Every proximity check in this project — the gaze, the Hydra's cauterising, the Argo's crew, Orpheus
looking back — is written this way, and every one of them was a bug first.

If you need to know where somebody *is*, ask on **their** region:

```kotlin
context.schedulers.entity(her) {
    val where = her.location
    context.schedulers.entity(him) { him.sendMessage("She is at $where") }
}
```

Also: `world.entities` reaches across every region on the server. `getNearbyEntities` does not.

## Teleports → `teleportAsync`. Always.

Thread-safe from anywhere. It returns a `CompletableFuture`; do the follow-up in `.thenRun {}` and hop
back onto the entity's scheduler inside it.

## Disk → `schedulers.async`. Always.

Never touch a file from a region thread.

## Blocks → the region that owns them

`schedulers.region(location) { ... }`. `TerraformService.heal` does this for you, and dribbles the
work across ticks — putting 200,000 blocks back in one go is how you make a Folia server stop
answering.

## Worlds can only be created during startup

Which is why `realms.register` is declarative and the engine builds them all at the tail of
`onEnable`.
