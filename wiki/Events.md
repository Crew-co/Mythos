# Events

Every hook the engine fires. They live in the addon API, so every addon sees the **same class object** —
never shade the API, or Bukkit's `HandlerList` won't match.

## Roles

| Event | Cancel it to… |
|---|---|
| `RoleClaimEvent` | **forbid a claim.** Set `denyReason`. |
| `RoleClaimedEvent` | *(not cancellable)* — they are Gaia now. Grant items, set spawn, start their story. |
| `RoleReleasedEvent` | *(not cancellable)* — a mantle has fallen vacant. |
| `RoleRetiringEvent` | **keep a character on stage** when their age ends. This is how the Odyssey inherits Odysseus from the Iliad. |
| `PlayerBecameSpiritEvent` | *(not cancellable)* — they've been dissolved. `ChthonicRealm` uses this to send them to the House of Hades. |
| `PlayerReincarnatingEvent` | **stop the engine putting a dead player back in a body.** `ChthonicRealm` cancels it until you've crossed the river. |

## Death

| Event | |
|---|---|
| `DivineDeathEvent` | Fired with the blow **pre-cancelled** when the tiers say a kill is impossible — and a story can **un-cancel it**. |

That's how *"only the adamantine sickle can unmake Uranus"* lives in EraOfCreation instead of being
hard-coded in the engine. And it's the same hook as Achilles' heel, ten addons later.

```kotlin
@EventHandler(priority = EventPriority.HIGH)
fun onDivineDeath(event: DivineDeathEvent) {
    if (event.victimRole.id != "uranus") return
    val sickle = event.killer?.inventory?.itemInMainHand
    event.isCancelled = !isSickle(sickle)      // nothing else in the world can do it
    event.unmakes = true
}
```

## The story

| Event | |
|---|---|
| `EraAdvancedEvent` | the world turns. **The hook every downstream addon waits on.** |
| `ObjectiveCompletedEvent` | a beat has been struck |
| `PowerUseEvent` | **cancellable.** Zeus's `/power decree` cancels every god's powers for three minutes — including powers from addons written years later. |
| `MythosResetEvent` | the world is being wiped. **Clean up your own state**, because the engine has no idea you keep one. |
