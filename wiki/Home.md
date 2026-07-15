# Mythos

**A Greek mythology engine for Folia.** Roles, spirits, eras, powers, realms — and not one line of
Greek mythology in the plugin itself. Chaos, Kronos and Achilles are *addons*, and they are the only
things that are.

> The engine knows about roles that can be claimed and lost, spirits who queue for them, eras that
> turn when their objectives are struck, worlds with rules about who may stand in them, and a history
> that writes itself. It has never heard of Zeus.

## Pages

| | |
|---|---|
| **[Getting Started](Getting-Started)** | install, first boot, the addon folder |
| **[Architecture](Architecture)** | the host, the engine, the addons, and why the classloaders matter |
| **[Roles and Spirits](Roles-and-Spirits)** | claiming, the queue, essence, succession, retirement |
| **[Eras and Stories](Eras-and-Stories)** | the chain, splicing, the narrator, the chronicle |
| **[Realms and Gateways](Realms-and-Gateways)** | worlds, access rules, doors, item-keys, terraform |
| **[Powers](Powers)** | what a role lets you do |
| **[Writing an Addon](Writing-an-Addon)** | the whole tutorial, start to finish |
| **[Extending Another Addon](Extending-Another-Addon)** | extension points, `roles.extend`, `realms.grant` |
| **[Commands](Commands)** | every command, and who can run it |
| **[Configuration](Configuration)** | `config.yml`, annotated |
| **[Events](Events)** | every hook, and what cancelling it does |
| **[Folia Notes](Folia-Notes)** | the threading rules that will actually bite you |
| **[Troubleshooting](Troubleshooting)** | the five ways an addon fails to load |

## The stories

Nine, in chain order. Each is its own repository and its own jar.

```
chaos → titanomachy → olympian-order → the-abduction → theft-of-fire
  → ages-of-man → the-heroic-age → the-labours → the-golden-fleece
```

| Addon | Era | What it is |
|---|---|---|
| [EraOfCreation](https://github.com/Crew-co/EraOfCreation) | `chaos` | eight seats, and everyone else is a spirit |
| [Titanomachy](https://github.com/Crew-co/Titanomachy) | `titanomachy` | Kronos eats his children. Ten years of war. |
| [OlympianOrder](https://github.com/Crew-co/OlympianOrder) | `olympian-order` | the lots, Olympus, and **mortals** |
| [ChthonicRealm](https://github.com/Crew-co/ChthonicRealm) | `the-abduction` | death becomes a place. Charon gets paid. |
| [TheftOfFire](https://github.com/Crew-co/TheftOfFire) | `theft-of-fire` | Prometheus, Pandora, and an eagle that doesn't stop |
| [AgesOfMan](https://github.com/Crew-co/AgesOfMan) | `ages-of-man` | the flood |
| [Perseus](https://github.com/Crew-co/Perseus) | `the-heroic-age` | a monster who kills by being looked at |
| [LaboursOfHeracles](https://github.com/Crew-co/LaboursOfHeracles) | `the-labours` | twelve, for something he didn't do |
| [Argonautica](https://github.com/Crew-co/Argonautica) | `the-golden-fleece` | fifty players on one boat |

## The one rule

```kotlin
compileOnly("net.crewco:mythos-addon-api:0.1.0")     // ✅
implementation("net.crewco:mythos-addon-api:0.1.0")  // ❌ silent, baffling failure
```

Shade the API into an addon and you get a second class with the same name. `isAssignableFrom` fails,
the addon refuses to load, and the error will point at nothing useful. See [Troubleshooting](Troubleshooting).
