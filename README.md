# EaglerLocustFall26

A Paper 1.21.11 classroom plugin for the Eaglercraft server project.

EaglerLocust creates one Herobrine-like night stalker. It only chooses a target when a Survival/Adventure player is isolated from everyone else. The entity initially spawns in cover with both its X and Z offsets at least 20 blocks from the target, then follows from behind while the player is looking away.

## Behavior

- Only one Locust/Herobrine can exist on the server at once.
- At night, the plugin looks for isolated players. If everyone is grouped up, nothing spawns.
- A spawn point must be safe, hidden behind terrain, and not closer to another player than to the target.
- The visible entity is Paper 1.21.11's player-shaped `Mannequin`, using a classic Herobrine skin and no visible custom name/description.
- While the target looks away, Herobrine closes the distance and tries to remain behind them.
- If the target turns toward Herobrine, it freezes rather than walking into view.
- If the target looks directly at Herobrine, it flees quickly, reaches cover, disappears, and may return after a 60-second cooldown.
- Opening a chest, furnace, crafting table, or other server inventory triggers an ambush. Clicking/dragging in the player's own inventory also creates a short distraction window.
- Each successful hit deals 16 HP of raw damage (8 hearts before armor reduction).
- Herobrine leaves after its target dies, at morning, when the target disconnects/changes world, or when an escape attempt times out.

## Teacher commands

All commands require OP by default.

```text
/locust status
/locust force <player>
/locust dismiss
/locust reload
```

`/locust force <player>` is intended for classroom testing so you do not need to wait for a natural night cycle.

## Build

Requirements: Java 21 and Maven.

```bash
mvn clean package
```

Output:

```text
target/EaglerLocustFall26-1.0.0.jar
```

GitHub Actions also keeps the current classroom-ready build at:

```text
dist/EaglerLocustFall26-1.0.0.jar
```

## Classroom server integration

The companion server repository uses its plugin picker to download the JAR from this repository. Once this repo has a successful build, choose **Eagler Locust Fall 2026** in the server's **Classroom Plugin Lab** when running `bash startup.sh`.

All gameplay tuning lives in `src/main/resources/config.yml` and becomes `plugins/EaglerLocustFall26/config.yml` after first launch.

## Compatibility note

The plugin intentionally uses Paper's native 1.21.11 Mannequin entity instead of ProtocolLib or a fake-player/NMS implementation. That keeps the plugin self-contained. The classroom server's TuffXPlus/ViaEntities layer is responsible for translating modern entities to the browser client.
