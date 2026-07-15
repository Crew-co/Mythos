# Commands

## Everyone

| | |
|---|---|
| `/claim` | opens the **Altar of Fate** — what you can take, and, greyed out with the real reason, what you can't |
| `/claim <role>` · `/claim list` | take a mantle · the text version |
| `/roles [era]` | every role, and who wears it |
| `/role` · `/role heir <p>` · `/role abdicate confirm` | your mantle |
| `/spirit` | your essence, your place in the queue, what's open to you |
| `/spirit queue <role\|any>` · `/spirit decline` · `/spirit list` | wait · pass an offer on · who else is waiting |
| `/era` · `/era all` | where the story stands · the whole shape of it |
| `/power` | your powers, and what's off cooldown |
| `/power <id> [args]` | use one |
| `/chronicle` · `/chronicle era <id>` | what has happened, and who did it |

## `mythos.admin`

| | |
|---|---|
| `/mythos dev` | **solo mode** — gates and cooldowns off, crowd-sized numbers become 1 |
| `/mythos reset story confirm` | rewind the ages. Players keep essence, epithets, past lives. |
| `/mythos reset world confirm` | everything, including the Chronicle |
| `/mythos reset player <n> confirm` · `reset powers [p]` · `reset chronicle confirm` | |
| `/mythos assign <player> <role>` | force a role, bypassing every gate |
| `/mythos release <player>` · `seal <role>` · `open <role>` | |
| `/mythos advance <era>` · `/mythos complete <objective>` | force the story |
| `/mythos essence <player> <n>` | |
| `/mythos realms` · `/mythos realm <id>` | the cosmos · go there |
| `/mythos gateways` | every door, and where it goes |
| `/mythos scars` · `/mythos heal <scar\|all>` | temporary damage to the world · put it back |
| `/mythos points` | every extension point anyone has opened or posted to |
| `/mythos save` | flush to disk |
| `/addons` · `/addons reload` | the loader |

> **`/addons reload` does not unregister commands.** Bukkit's command map has no clean removal; a
> reloaded addon re-registers and the newest wins. Fine for dev. Restart for production.
