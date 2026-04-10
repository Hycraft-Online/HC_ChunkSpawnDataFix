# HC_ChunkSpawnDataFix

Hyxin mixin plugin that fixes several NullPointerException and ArrayIndexOutOfBoundsException crashes in the Hytale server's chunk and spawning systems. The vanilla server uses `assert` statements for null checks which are disabled in production, causing crashes when chunks unload mid-tick or when saved world data has a different section count than expected.

## Features

- Patches `WorldSpawningSystem.tick()` to null-guard `ChunkSpawnData` in the unspawnable chunk loop
- Patches `WorldSpawningSystem.createRandomSpawnJob()` to check `ChunkSpawnData` before adding `SpawnJobData`, preventing orphaned components on early return
- Patches `WorldSpawningSystem.pickRandomChunk()` to null-guard both `ChunkSpawnData` and `ChunkSpawnedNPCData` in iteration loops and lambda functions
- Patches `BlockHealthSystem.tick()` to handle stale player entity references and null `ChunkTracker` components during player disconnect/teleport transitions
- Patches `BlockChunk.loadFromHolder()` to clamp section iteration when saved chunk data has more sections than the current server expects (e.g., 11 vs 10 from worlds saved with a different height)
- Deployed as a Hyxin early plugin (loads into `earlyplugins/` directory)

## Dependencies

None (standalone mixin plugin).

## Building

```
./gradlew build
```
