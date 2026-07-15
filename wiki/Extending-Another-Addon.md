# Extending Another Addon

Three mechanisms, in increasing order of nerve.

## 1. Extend a role you don't own

```kotlin
// In Titanomachy. Gaia belongs to EraOfCreation.
mythos.roles.extend("gaia") { it.copy(powers = it.powers + "prophesy") }
```

She watched her own husband get cut down by his son; in *this* age she can tell you how it ends.
Holders keep the role and simply gain what you added.

**Returns `false` if the role isn't registered** — not an error, just a story that isn't being told on
this server. Every `extend` in the project is written to shrug.

Do it in `schedulers.globalDelayed(1)`, when every jar has registered its cast.

## 2. Splice a chapter into the chain

```kotlin
mythos.eras.insertAfter("olympian-order", RAPE_OF_PERSEPHONE)
```

The pointers re-link at bootstrap. Neither neighbour is edited, and neither knows.

## 3. Open a hole in yourself and let strangers post through it

```kotlin
// Titanomachy, which does not know who fought in its own war:
mythos.extensions.consume<WarAlly>(WarAlly.POINT) { ally ->
    mythos.roles.register(roleFor(ally))
    mythos.eras.addObjective(ERA, Objective("ally_${ally.id}", "...", optional = true))
}

// Some jar written a year later:
mythos.extensions.contribute(WarAlly.POINT, WarAlly("styx", "Styx", OLYMPIAN, "First to come over."))
```

**Load order does not matter.** `consume` replays every contribution already posted and receives every
one posted afterwards. That single property is what makes an ecosystem possible instead of a fragile
chain of hard `depends:`.

### The points that exist today

| Point | Owner | Post one of these and… |
|---|---|---|
| `heracles:labours` | LaboursOfHeracles | …it becomes a **required** objective. A thirteenth labour holds the age open. |
| `perseus:gorgons` | Perseus | …it kills by being looked at. You get the raycast, the warning tick, the mirror counter, the statues. |
| `argo:landfalls` | Argonautica | …it becomes a leg of the voyage, in order, with the whole crew aboard. |
| `olympus:seats` | OlympianOrder | …a god takes a throne. The Twelve were never a fixed list. |
| `prometheus:liberation` | TheftOfFire | …somebody can take him off the rock. |
| `chthonic:retrieval` | ChthonicRealm | …somebody can bring the dead back. |
| `titanomachy:allies` | Titanomachy | …they join a side of the war. |

### If your contribution carries behaviour

You need the point owner's *type* at compile time:

```kotlin
compileOnly("net.crewco:theft-of-fire:0.1.0")
```
```yaml
depends: [ TheftOfFire ]
```

The host's classloader delegation resolves it at runtime to the one true class. **Never shade it.**

That's the only hard `depends:` in the entire project — `LaboursOfHeracles → TheftOfFire`, so that a
man in a lion's skin can walk past a rock and take somebody off it.

## 4. Open a new way into a realm you don't own

```kotlin
mythos.realms.grant("underworld", RealmKeys.bearing(BOUGH))
```

`roles.extend`, for places. See [Realms and Gateways](Realms-and-Gateways).
