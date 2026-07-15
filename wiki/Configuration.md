# Configuration

`plugins/Mythos/config.yml`

```yaml
claiming:
  # Every role additionally needs mythos.claim.<roleid>. On for a curated cast;
  # off for a free-for-all scramble at world start.
  require-permission: false

  # When a mantle falls vacant, offer it to the front of the spirit queue rather
  # than letting whoever types fastest take it.
  queue-priority: true
  offer-seconds: 60

  # A god who abdicates can't immediately grab another role. On a 100+ server where
  # the rank and file die constantly, drop this to ~30 — it's there to stop role-hopping,
  # not to bench half your playerbase.
  cooldown-seconds: 300

  # THE SETTING THAT MAKES 100+ PLAYERS WORK.
  # Where roleless players land instead of the spirit world. Leave it EMPTY during the
  # Age of Chaos — there is nothing to be yet, and that is the point of the Age of Chaos.
  # Set it to "mortal" once OlympianOrder's chapter begins.
  default-role: ""

  # A threshold to even try for a role of each tier. What's actually deducted.
  essence-cost:
    PRIMORDIAL: 0      # the first age is free — nobody has had time to earn anything
    TITAN: 20
    OLYMPIAN: 40
    CHTHONIC: 30
    MONSTER: 10
    DEMIGOD: 10
    HERO: 5
    MORTAL: 0

spirit:
  mode: ADVENTURE       # or SPECTATOR — a camera instead of a presence
  invisible: true
  can-fly: true
  essence-per-interval: 1
  interval-minutes: 5
  essence-on-era-advance: 10
  essence-on-divine-death: 5

death:
  # RELEASE = permadeath. Killed = the mantle is torn away = back to the queue.
  # KEEP    = death is only a setback.
  on-divine-death: RELEASE
  release-after-offline-hours: 72

eras:
  # The cast of a finished story goes back to the spirit world — with an epithet and
  # a pocket of essence. Endurance.ETERNAL roles are untouched.
  retire-cast-on-advance: true
  retirement-essence: 25

  # Ticks of dark, held silence between one age's epilogue and the next age's prologue.
  # Nothing can be claimed during a transition. This is what makes a story LAND.
  interlude-ticks: 60

display:
  hud: true             # boss bar (the age + its progress) and sidebar
  prefix-names: true
  broadcast-claims: true
  broadcast-eras: true
```

## Files the engine writes

| | |
|---|---|
| `state.yml` | current era, objectives struck, who holds what, the spirit queue, dev mode |
| `chronicle.yml` | the history of the world. Nobody writes it; it accumulates. |
| `scars.yml` | unhealed temporary damage. Survives a crash mid-flood. |
| `gateways.yml` | every door |
| `players/<uuid>.yml` | role, essence, favor, titles, past lives, flags |

Addons write their own files into `plugins/Mythos/addons/<Name>/`.
