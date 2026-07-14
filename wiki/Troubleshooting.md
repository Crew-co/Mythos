# Troubleshooting

## The addon doesn't load

**`Addon::class.java.isAssignableFrom(...)` fails / silent refusal**
You shaded the API. `compileOnly`, always. A shaded copy is a different class with the same name.

**`api-version` mismatch**
The current API version is **2**. The loader refuses a mismatch rather than letting it die later with
a `NoSuchMethodError`, which is exactly what that gate is for.

**`NoClassDefFoundError: net/crewco/mythos/fire/Liberation`**
You're using another addon's type without `depends: [ TheftOfFire ]` in your `addon.yml`. Addon
classloaders only see the addons they declare.

## `Mythos.from(context)` throws

> *"RoleService is unavailable. The Mythos engine failed to start."*

The engine died during `onEnable`. Look **above** the addon load lines in the log — a bad `config.yml`
or a corrupt `state.yml` is the usual cause.

## The power does nothing

> *"That power is not yours to wield."*

**The id isn't in the role's `powers` list.** `PowerService` checks it before firing. A power that
isn't in one is dead code. I have shipped this bug twice; it is the most common mistake in the project.

## The era never advances

`/era` shows what's left. A **required** objective is outstanding — possibly one contributed by another
addon (a labour, a landfall, an ally). `/mythos complete <objective>` forces it.

If the log says *"Era 'x' points at 'y', which no addon registered"* — the next chapter isn't installed.
The story stops there and says so.

## Nothing happens when I test alone

`/mythos dev`. The Titanomachy wants 200 kills; the lots want three brothers in a room. See
[Getting Started](Getting-Started).

## The world is still flooded

`/mythos scars` → `/mythos heal all`. A story that breaks the world writes through a **Scar**; if you
wrote blocks directly, the engine cannot help you.

## The other worlds didn't generate / everyone is stuck in the overworld

**You need to restart once.** Folia will not create a world at runtime; the engine has written the
realms into `bukkit.yml` and printed a box telling you so. Restart and they generate.

`/mythos realms` shows which exist and which don't. Anything that tries to send a player to a missing
realm now says so — to the player, and in the log — rather than silently doing nothing, which is what
made this look like a broken *story* rather than a missing *world*.

## Something threw on a region thread

You read a foreign player's `Location`. See [Folia Notes](Folia-Notes) — this is the single most common
runtime error, and `getNearbyEntities` is almost always the answer.
