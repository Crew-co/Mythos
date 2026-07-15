# Mythos

**A Greek mythology engine for Folia.**

Roles, spirits, eras, powers, realms, a narrator, a chronicle — and **not one line of Greek mythology in
the plugin itself**. Chaos, Kronos and Achilles are addons, and they are the only things that are.

📖 **[Read the wiki →](https://github.com/Crew-co/Mythos/wiki)**

---

## What it actually is

A **role** is a name somebody can hold. There are eight in the Age of Chaos, and everyone else in the
world is a **spirit** — bodiless, in a queue, earning essence by watching history happen and waiting for
a mantle to fall vacant.

An **era** is a chapter with objectives. When the last one is struck, the world advances to whatever era
declared itself next — usually registered by a jar the current one has never heard of. The old story's
epilogue plays, the world goes dark and holds still, and the next story's prologue begins.

**Every story is a separate repository, a separate jar, and a separate release cycle.** They do not
import each other. Delete one and the chain re-links itself around the hole.

```
chaos → titanomachy → olympian-order → the-abduction → theft-of-fire
  → ages-of-man → the-heroic-age → the-labours → the-golden-fleece
```

## The nine stories

| Addon                                                                     | Era | |
|---------------------------------------------------------------------------|---|---|
| [EraOfCreation](https://github.com/Crew-co/Mythos-EraOfCreation)          | `chaos` | eight seats. The Titans cannot be claimed — they are **born**, and only Gaia can bear them. |
| [Titanomachy](https://github.com/Crew-co/Mythos-Titanomachy)              | `titanomachy` | Kronos's stomach is **a world**, and the gods he ate are in it, together. |
| [OlympianOrder](https://github.com/Crew-co/Mythos-OlympianOrder)          | `olympian-order` | the lots are drawn **blind** — and a god's powers only work in the domain his hand closed on. |
| [ChthonicRealm](https://github.com/Crew-co/Mythos-ChthonicRealm)          | `the-abduction` | death becomes a place. **The spirit queue now runs through a player's hands**, and he charges. |
| [TheftOfFire](https://github.com/Crew-co/Mythos-TheftOfFire)              | `theft-of-fire` | it **cannot finish its own story**. Prometheus waits on the rock for a jar that doesn't exist yet. |
| [AgesOfMan](https://github.com/Crew-co/Mythos-AgesOfMan)                  | `ages-of-man` | the flood. Everyone dies at once, and two people throw rocks over their shoulders to bring them back **in order**. |
| [Perseus](https://github.com/Crew-co/Mythos-Perseus)                      | `the-heroic-age` | Medusa is a player. **She kills by being looked at.** |
| [LaboursOfHeracles](https://github.com/Crew-co/Mytrhos-LaboursOfHeracles) | `the-labours` | the twelfth labour needs **Hades — a player — to say yes**, and he may not. |
| [Argonautica](https://github.com/Crew-co/Mythos-Argonautica)              | `the-golden-fleece` | fifty players on one boat, and **Medea can simply refuse**. |

## Install

1. `Mythos-0.1.0.jar` → `plugins/`
2. Story jars → `plugins/Mythos/addons/`
3. Restart.

Requires **Folia 1.21.11** and **Java 21**. See **[Getting Started](https://github.com/Crew-co/Mythos/wiki/Getting-Started)**.

## Writing a story

```kotlin
class TrojanWarAddon : AddonBase() {
    override fun onEnable() {
        val mythos = Mythos.from(context)          // no depends: — the engine is the plugin

        mythos.eras.register(TROJAN_WAR)           // naming the era that FOLLOWS it
        mythos.roles.register(ACHILLES)
        mythos.powers.register(RagePower())
        context.registerListener(WarListener())
    }
}
```

**[Writing an Addon →](https://github.com/Crew-co/Mythos/wiki/Writing-an-Addon)**

## Built on

- [FoliaPluginTemplate-Addon-Based-Version](https://github.com/Crew-co/FoliaPluginTemplate-Addon-Based-Version) — the host
- [FoliaAddonTemplate](https://github.com/Crew-co/FoliaAddonTemplate) — every story

Two changes to the host worth knowing: **addon classloaders now delegate to their `depends:`** (so one
addon can publish an API another builds on), and a **service-registry leak** on unload is fixed. See
[Architecture](https://github.com/Crew-co/Mythos/wiki/Architecture).
