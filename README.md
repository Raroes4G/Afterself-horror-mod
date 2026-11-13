<div align="center">

# Afterself

**Every death leaves a soul behind.**

A Minecraft horror mod where your dead come back at night with your face, to stalk you, rob you, trap your friends and take your place.

![Minecraft](https://img.shields.io/badge/Minecraft-1.19.2%20%7C%201.20.1%20%7C%201.21%20%7C%201.21.1-62B47A)
![Loaders](https://img.shields.io/badge/Loaders-Forge%20%7C%20NeoForge-E04E14)
![Requires](https://img.shields.io/badge/Requires-Simple%20Voice%20Chat%20%2B%20GeckoLib-4A90D9)
![License](https://img.shields.io/badge/License-GPL--3.0--only-blue)


</div>

> [!WARNING]
> Horror mod with jumpscares, loud screams and flashing red images. A calm screamer without flashing, shaking or tearing images is one setting away (`screamerIntense=false`), and the screamer can be turned off on your client (`screamer=false`).

## Features

- **Your soul takes everything.** Nothing drops when you die. Your soul keeps your items, and you have to kill it to get them back.
- **It only exists at night.** At sunrise it fades away with your items, and the next night it comes back wherever you are. In the Nether and the End it never sleeps.
- **It hunts by sound.** Sprinting, talking and shouting in Simple Voice Chat give you away. It only sees what is in front of it. When it loses you, it searches the last place it saw you.
- **It steals.** It takes weapons, armour, totems and food from your chests, then equips them.
- **It fights like a player.** It lands critical hits, raises a shield, uses bows and ender pearls, bridges gaps, pillars up, digs through walls, and runs away to eat when it is losing.
- **It sets traps.** From afar it looks exactly like the dead player: the real name, no particles, the real voice. It waves, kneels, cries or plays dead to draw your friends in.
- **It takes your body.** If it kills you during a trap, your body walks to the other players. It finds its way around walls, opens doors and defends itself.
- **The screamer.** Your own face, hollow-eyed, rushes at you. There are six of them, each with its own scream, and they are rare on purpose: at least 15 minutes between two.
- **Souls merge.** Past 10 souls, the two weakest merge into a stronger one.

## Download

Download Afterself from the [Releases](../../releases) page, or from Modrinth.

Put these three jars in the `mods` folder, **on the client and on the server**:

1. The Afterself jar for your version.
2. [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat).
3. [GeckoLib](https://modrinth.com/mod/geckolib).

| Minecraft | Loaders | Afterself jar | GeckoLib |
|---|---|---|---|
| 1.19.2 | Forge | `afterself-1.19.2-forge-<version>.jar` | 3.1.x |
| 1.20.1 | Forge, NeoForge | `afterself-1.20.1-forge-neoforge-<version>.jar` | 4.4 to 4.x |
| 1.21, 1.21.1 | Forge, NeoForge | `afterself-1.21-1.21.1-forge-neoforge-<version>.jar` | 4.5 to 4.x |

Every player must finish Voice Chat's first-launch setup screen. Until then, they cannot hear the souls.

## Voice and privacy

Afterself listens to how loud you are in Simple Voice Chat. Short clips of your voice are only kept **if you agree**: every player is asked in every world, and no answer means nothing is recorded. The clips stay on the server's disk, and `/afterself voice refuse` or `/afterself voice forget` erases them at any time.

## Configuration and commands

`config/afterself.properties` is created on first launch. Every feature can be switched off on its own: block breaking and placing, looting, stealing, scares, bed haunting, possession, voice clips and the screamer.

- `/afterself voice accept | refuse | forget | status`: your voice clips. Every player can use it.
- `/afterself debug ...`: spawn a soul, start a trap or a scare, possess or release a player, fire the screamer. Operators only.

## Building from source

You need a JDK 21 and Bash (Git Bash works on Windows). Gradle downloads the JDK 17 needed by 1.19.2 and 1.20.1 by itself.

```bash
bash build-all.sh            # builds the three jars into jars/
bash build-all.sh 1.20.1     # builds a single version
```

| Folder | Contents |
|---|---|
| `shared/` | Code and resources shared by every version: AI, voice, screamer, models, animations, sounds |
| `1.19.2/`, `1.20.1/`, `1.21/` | Only what changes between game versions and loaders |
| `testharness/` | The automated in-game test client, never included in the jars |
| `logo/`, `docs/`, `modrinth/`, `github/` | Logo, screenshots and publishing texts |

### Tests

`bash run-client-test.sh <target>` starts a real game client and runs about 180 checks in a test world: AI, combat, building, traps, possession, voice, screamer and rendering. It saves screenshots in `testharness/shots/`. The targets are `forge-1.19.2`, `forge-1.20.1`, `neoforge-1.20.1`, `forge-1.21.1` and `neoforge-1.21.1`.

### Animating the soul

The soul's model and animations are GeckoLib files in `shared/resources/assets/afterself/`, editable in Blockbench with the GeckoLib plugin. Keep the animation and bone names, and no code needs to change.

## Known limitations

- The possessed body does not climb ladders, swim underwater, build, or open iron doors and fence gates.
- A soul whose owner is offline uses a default skin.
- The voice loudness thresholds have not been tuned with a real microphone on every version.

## License

Afterself is free software, released under the [GNU General Public License v3.0](LICENSE) (`GPL-3.0-only`).
