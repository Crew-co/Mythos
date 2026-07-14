# Getting Started

## Requirements

- **Folia** 1.21.11 (Paper's regionised fork)
- **Java 21**

## Install

1. `Mythos-0.1.0.jar` → `plugins/`
2. Story jars → `plugins/Mythos/addons/` *(the folder is created on first boot)*
3. Restart. `/addons` lists what loaded.

**Order doesn't matter.** The engine wires the era chain at bootstrap, after every jar has spoken —
this used to depend on the alphabetical order of the files in the folder, which is not a property you
want a mythology to have.

## ⚠️ First boot needs TWO starts

**Folia will not create a world while the server is running.** So on the very first boot, the engine
writes every realm an addon declared into `bukkit.yml` and tells you, in a box, to restart:

```
+------------------------------------------------------------------+
|  3 REALM(S) HAVE NOT GENERATED YET.
|     void -> mythos_void
|     tartarus -> mythos_tartarus
|     olympus -> mythos_olympus
|
|  Folia will not create a world while the server is running, so
|  they have been written into bukkit.yml instead.
|
|  >>> RESTART THE SERVER. <<<
+------------------------------------------------------------------+
```

Restart, and Bukkit loads them at boot using the plugin's generator. **You will never see it again.**
Install a new story addon that declares a new realm, and you get the box once more, for that realm.

> Until you restart, anything that sends a player to a missing realm **does nothing**, and the story
> looks broken. Because it is. The player is told so, and so is the log.

## First boot

```
[Mythos] Engine awake: 0 roles, 0 eras. Waiting on the stories.
[Mythos] Loaded addon: EraOfCreation
[Mythos] Realm 'void' → world 'mythos_void'
[Mythos] Realm 'tartarus' → world 'mythos_tartarus'
[Mythos] Registered era 'chaos' (#0 → titanomachy)
...
[Mythos] Resuming the age of 'chaos'.
```

Worlds are generated **once, during startup**. Folia does not permit creating a world on a region
thread while the server is running, and honestly neither should we: the shape of the universe is not
a runtime decision.

## Building alone

You cannot playtest a hundred-player mythology with one player. The Titanomachy wants 200
cross-faction kills; the lots want three brothers in a room; twelve thrones want twelve people.

```
/mythos dev
```

Two things happen. **Admins skip every gate** — claim rules, essence costs, queue priority, all
cooldowns. And **every crowd-sized number becomes 1**: one kill ends the war, one throne is twelve
thrones, one swallowed child is five.

It announces itself loudly, because a server where one person can be Zeus, Kronos and Gaia in the
same afternoon should not quietly pretend to be anything else.

## Starting over

```
/mythos reset story confirm     rewind the ages — players KEEP essence, epithets, past lives
/mythos reset world confirm     everything, including the Chronicle
```

`reset story` is the one you want while building. It fires `MythosResetEvent`, which is how the
*addons* clean up after themselves — the engine has no idea Titanomachy keeps a kill tally.

## Which chapter am I in?

```
/era          where the story stands, and what has to happen next
/era all      the whole shape of it
/chronicle    what has already happened, and who did it
/mythos realms
```
