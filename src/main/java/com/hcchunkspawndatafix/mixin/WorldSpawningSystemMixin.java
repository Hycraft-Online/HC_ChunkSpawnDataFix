package com.hcchunkspawndatafix.mixin;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.ResourceType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.random.RandomExtra;
import com.hypixel.hytale.math.util.MathUtil;
import com.hypixel.hytale.server.core.asset.type.environment.config.Environment;
import com.hypixel.hytale.server.core.asset.type.gameplay.GameplayConfig;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.flock.config.FlockAsset;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.spawning.SpawningPlugin;
import com.hypixel.hytale.server.spawning.world.ChunkEnvironmentSpawnData;
import com.hypixel.hytale.server.spawning.world.WorldEnvironmentSpawnData;
import com.hypixel.hytale.server.spawning.world.WorldNPCSpawnStat;
import com.hypixel.hytale.server.spawning.world.component.ChunkSpawnData;
import com.hypixel.hytale.server.spawning.world.component.ChunkSpawnedNPCData;
import com.hypixel.hytale.server.spawning.world.component.SpawnJobData;
import com.hypixel.hytale.server.spawning.world.component.WorldSpawnData;
import com.hypixel.hytale.server.spawning.world.system.WorldSpawningSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Set;
import java.util.logging.Level;

/**
 * Mixin to fix NullPointerException in WorldSpawningSystem when ChunkSpawnData
 * or ChunkSpawnedNPCData components are null due to chunk unloading during iteration.
 *
 * The vanilla code relies on assert statements which are disabled in production,
 * causing NPEs when chunks are partially initialized or unloaded mid-tick.
 *
 * This mixin overwrites tick(), createRandomSpawnJob(), and pickRandomChunk()
 * to replace all assert-based null checks with proper null guards.
 */
@Mixin(WorldSpawningSystem.class)
public abstract class WorldSpawningSystemMixin {

    private static final HytaleLogger LOGGER = HytaleLogger.get("HC_ChunkSpawnDataFix");

    // --- Shadow fields from WorldSpawningSystem ---

    @Shadow
    private ResourceType<EntityStore, WorldSpawnData> worldSpawnDataResourceType;

    @Shadow
    private ComponentType<ChunkStore, ChunkSpawnData> chunkSpawnDataComponentType;

    @Shadow
    private ComponentType<ChunkStore, ChunkSpawnedNPCData> chunkSpawnedNPCDataComponentType;

    @Shadow
    private ComponentType<ChunkStore, SpawnJobData> spawnJobDataComponentType;

    @Shadow
    private ComponentType<ChunkStore, WorldChunk> worldChunkComponentType;

    // --- Shadow static methods from WorldSpawningSystem ---

    @Shadow
    private static int getAndConsumeNextEnvironmentIndex(@Nonnull WorldSpawnData worldSpawnData, @Nonnull int[] environmentKeySet) {
        throw new AssertionError("Shadow");
    }

    @Shadow
    private static boolean getAndUpdateSpawnCooldown(@Nonnull ChunkSpawnData chunkSpawnData) {
        throw new AssertionError("Shadow");
    }

    // --- Init logging ---

    @Inject(method = "<init>", at = @At("RETURN"))
    private void onInit(CallbackInfo ci) {
        LOGGER.at(Level.INFO).log("WorldSpawningSystem patched with null-safety checks (tick + createRandomSpawnJob + pickRandomChunk)");
    }

    // =====================================================================
    // tick() - null guard on ChunkSpawnData in unspawnable chunk processing
    // =====================================================================

    /**
     * @author HC_ChunkSpawnDataFix
     * @reason Fix NPE when ChunkSpawnData is null in unspawnable chunk loop
     */
    @Overwrite
    public void tick(float dt, int systemIndex, @Nonnull Store<ChunkStore> store) {
        World world = store.getExternalData().getWorld();
        if (!world.getWorldConfig().isSpawningNPC() || world.getPlayerCount() == 0) {
            return;
        }
        GameplayConfig gameplayConfig = world.getGameplayConfig();
        Store<EntityStore> entityStore = world.getEntityStore().getStore();
        WorldSpawnData worldSpawnDataResource = entityStore.getResource(this.worldSpawnDataResourceType);
        if (worldSpawnDataResource.isUnspawnable() ||
            world.getChunkStore().getStore().getEntityCount() == 0 ||
            gameplayConfig.getMaxEnvironmentalNPCSpawns() > 0 && worldSpawnDataResource.getActualNPCs() >= gameplayConfig.getMaxEnvironmentalNPCSpawns() ||
            (double)worldSpawnDataResource.getActualNPCs() > worldSpawnDataResource.getExpectedNPCs()) {
            return;
        }
        WorldTimeResource worldTimeResource = entityStore.getResource(WorldTimeResource.getResourceType());
        if (worldSpawnDataResource.hasUnprocessedUnspawnableChunks()) {
            while (worldSpawnDataResource.hasUnprocessedUnspawnableChunks()) {
                Environment environmentAsset;
                WorldSpawnData.UnspawnableEntry entry = worldSpawnDataResource.nextUnspawnableChunk();
                Ref<ChunkStore> chunkRef = world.getChunkStore().getChunkReference(entry.getChunkIndex());
                if (chunkRef == null) continue;
                int environmentIndex = entry.getEnvironmentIndex();

                // FIX: null guard replacing assert
                ChunkSpawnData chunkSpawnDataComponent = store.getComponent(chunkRef, this.chunkSpawnDataComponentType);
                if (chunkSpawnDataComponent == null) {
                    LOGGER.at(Level.FINE).log("Skipping unspawnable chunk - ChunkSpawnData null (chunk unloaded mid-tick)");
                    continue;
                }

                ChunkEnvironmentSpawnData environmentSpawnData = chunkSpawnDataComponent.getEnvironmentSpawnData(environmentIndex);
                int segmentCount = -environmentSpawnData.getSegmentCount();
                worldSpawnDataResource.adjustSegmentCount(segmentCount);
                WorldEnvironmentSpawnData worldEnvironmentSpawnData = worldSpawnDataResource.getWorldEnvironmentSpawnData(environmentIndex);
                double expectedNPCs = worldEnvironmentSpawnData.getExpectedNPCs();
                worldEnvironmentSpawnData.adjustSegmentCount(segmentCount);
                worldEnvironmentSpawnData.updateExpectedNPCs(worldTimeResource.getMoonPhase());
                environmentSpawnData.markProcessedAsUnspawnable();
                HytaleLogger.Api context = HytaleLogger.forEnclosingClass().at(Level.FINEST);
                if (!context.isEnabled() || (environmentAsset = Environment.getAssetMap().getAsset(environmentIndex)) == null) continue;
                String environment = environmentAsset.getId();
                context.log("Reducing expected NPC count for %s due to un-spawnable chunk. Was %s, now %s", environment, expectedNPCs, worldEnvironmentSpawnData.getExpectedNPCs());
            }
            worldSpawnDataResource.recalculateWorldCount();
        }
        int activeJobs = worldSpawnDataResource.getActiveSpawnJobs();
        int maxActiveJobs = SpawningPlugin.get().getMaxActiveJobs();
        while (activeJobs < maxActiveJobs &&
               worldSpawnDataResource.getActualNPCs() < MathUtil.floor(worldSpawnDataResource.getExpectedNPCs()) &&
               this.createRandomSpawnJob(worldSpawnDataResource, store, entityStore)) {
            activeJobs = worldSpawnDataResource.getActiveSpawnJobs();
        }
    }

    // =====================================================================
    // createRandomSpawnJob() - null guard BEFORE addComponent to prevent
    // orphaned SpawnJobData if chunk unloaded between pickRandomChunk and here
    // =====================================================================

    /**
     * @author HC_ChunkSpawnDataFix
     * @reason Fix NPE when ChunkSpawnData is null during spawn job creation.
     *         Null check moved BEFORE addComponent(SpawnJobData) to avoid leaking
     *         an orphaned SpawnJobData component on early return.
     */
    @Overwrite
    private boolean createRandomSpawnJob(@Nonnull WorldSpawnData worldData, @Nonnull Store<ChunkStore> chunkStore, @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        int environmentIndex;
        WorldEnvironmentSpawnData worldEnvironmentSpawnData;
        WorldNPCSpawnStat npcSpawnStat;
        int[] environmentDataKeySet = worldData.getWorldEnvironmentSpawnDataIndexes();
        do {
            if ((environmentIndex = getAndConsumeNextEnvironmentIndex(worldData, environmentDataKeySet)) != Integer.MIN_VALUE) continue;
            return false;
        } while ((npcSpawnStat = (worldEnvironmentSpawnData = worldData.getWorldEnvironmentSpawnData(environmentIndex)).pickRandomSpawnNPCStat(componentAccessor)) == null);
        int availableSlots = npcSpawnStat.getAvailableSlots();
        if (availableSlots == 0) {
            return false;
        }
        Ref<ChunkStore> chunkRef = this.pickRandomChunk(worldEnvironmentSpawnData, npcSpawnStat, worldData, chunkStore);
        if (chunkRef == null) {
            return false;
        }

        // FIX: Check ChunkSpawnData BEFORE adding SpawnJobData component.
        // Original code checked after addComponent, which would leak an orphaned
        // SpawnJobData if ChunkSpawnData was null.
        ChunkSpawnData chunkSpawnDataComponent = chunkStore.getComponent(chunkRef, this.chunkSpawnDataComponentType);
        if (chunkSpawnDataComponent == null) {
            LOGGER.at(Level.FINE).log("Aborting spawn job - ChunkSpawnData null after chunk pick (chunk unloaded mid-tick)");
            return false;
        }

        Environment environment = Environment.getAssetMap().getAsset(environmentIndex);
        HytaleLogger.Api context = HytaleLogger.forEnclosingClass().at(Level.FINER);
        if (context.isEnabled()) {
            WorldChunk worldChunkComponent = chunkStore.getComponent(chunkRef, this.worldChunkComponentType);
            if (worldChunkComponent != null) {
                String roleName = NPCPlugin.get().getName(npcSpawnStat.getRoleIndex());
                context.log("Trying SpawnJob env=%s role=%s chunk=[%s/%s] env(exp/act)=%s/%s npc(exp/act)=%s/%s", environment.getId(), roleName, worldChunkComponent.getX(), worldChunkComponent.getZ(), (int)worldEnvironmentSpawnData.getExpectedNPCs(), worldEnvironmentSpawnData.getActualNPCs(), (int)npcSpawnStat.getExpected(), npcSpawnStat.getActual());
            }
        }
        SpawnJobData spawnJobDataComponent = chunkStore.addComponent(chunkRef, this.spawnJobDataComponentType);
        FlockAsset flockDefinition = npcSpawnStat.getSpawnParams().getFlockDefinition();
        int flockSize = flockDefinition != null ? flockDefinition.pickFlockSize() : 1;
        int roleIndex = npcSpawnStat.getRoleIndex();
        if (flockSize > availableSlots) {
            flockSize = availableSlots;
        }
        spawnJobDataComponent.init(roleIndex, environment, environmentIndex, npcSpawnStat.getSpawnWrapper(), flockDefinition, flockSize);
        if (worldEnvironmentSpawnData.isFullyPopulated()) {
            spawnJobDataComponent.setIgnoreFullyPopulated(true);
        }
        chunkSpawnDataComponent.getEnvironmentSpawnData(environmentIndex).getRandomChunkColumnIterator().saveIteratorPosition();
        World world = chunkStore.getExternalData().getWorld();
        WorldSpawnData worldSpawnData = world.getEntityStore().getStore().getResource(WorldSpawnData.getResourceType());
        worldSpawnData.trackNPC(environmentIndex, roleIndex, flockSize, world, componentAccessor);
        HytaleLogger.Api finestContext = HytaleLogger.forEnclosingClass().at(Level.FINEST);
        if (finestContext.isEnabled()) {
            WorldChunk worldChunkComponent = chunkStore.getComponent(chunkRef, this.worldChunkComponentType);
            if (worldChunkComponent != null) {
                finestContext.log("Start Spawnjob id=%s env=%s role=%s chunk=[%s/%s]", spawnJobDataComponent.getJobId(), environment.getId(), NPCPlugin.get().getName(roleIndex), worldChunkComponent.getX(), worldChunkComponent.getZ());
            }
        }
        worldData.adjustActiveSpawnJobs(1, flockSize);
        return true;
    }

    // =====================================================================
    // pickRandomChunk() - null guards on ChunkSpawnData and ChunkSpawnedNPCData
    // in both iteration loops AND all three lambda functions
    // =====================================================================

    /**
     * @author HC_ChunkSpawnDataFix
     * @reason Fix NPE when ChunkSpawnData/ChunkSpawnedNPCData are null during chunk selection
     */
    @Overwrite
    @Nullable
    private Ref<ChunkStore> pickRandomChunk(@Nonnull WorldEnvironmentSpawnData spawnData, @Nonnull WorldNPCSpawnStat stat, @Nonnull WorldSpawnData worldSpawnData, @Nonnull Store<ChunkStore> store) {
        int roleIndex = stat.getRoleIndex();
        boolean wasFullyPopulated = spawnData.isFullyPopulated();
        Set<Ref<ChunkStore>> chunkRefSet = spawnData.getChunkRefSet();
        int environmentIndex = spawnData.getEnvironmentIndex();
        double weight = 0.0;
        boolean spawnable = false;
        boolean fullyPopulated = true;
        if (wasFullyPopulated) {
            for (Ref<ChunkStore> chunkRef2 : chunkRefSet) {
                // FIX: null guards replacing asserts
                ChunkSpawnData chunkSpawnDataComponent = store.getComponent(chunkRef2, this.chunkSpawnDataComponentType);
                if (chunkSpawnDataComponent == null) continue;
                ChunkSpawnedNPCData chunkSpawnedNPCDataComponent = store.getComponent(chunkRef2, this.chunkSpawnedNPCDataComponentType);
                if (chunkSpawnedNPCDataComponent == null) continue;
                ChunkEnvironmentSpawnData chunkEnvironmentSpawnData = chunkSpawnDataComponent.getEnvironmentSpawnData(environmentIndex);
                fullyPopulated = fullyPopulated && chunkEnvironmentSpawnData.isFullyPopulated(chunkSpawnedNPCDataComponent.getEnvironmentSpawnCount(environmentIndex));
                if (!chunkEnvironmentSpawnData.isRoleSpawnable(roleIndex)) continue;
                spawnable = true;
                weight += store.getComponent(chunkRef2, this.spawnJobDataComponentType) == null && !getAndUpdateSpawnCooldown(chunkSpawnDataComponent) ? 1.0 : 0.0;
            }
        } else {
            for (Ref<ChunkStore> chunkRef2 : chunkRefSet) {
                // FIX: null guards replacing asserts
                ChunkSpawnData chunkSpawnDataComponent = store.getComponent(chunkRef2, this.chunkSpawnDataComponentType);
                if (chunkSpawnDataComponent == null) continue;
                ChunkSpawnedNPCData chunkSpawnedNPCDataComponent = store.getComponent(chunkRef2, this.chunkSpawnedNPCDataComponentType);
                if (chunkSpawnedNPCDataComponent == null) continue;
                ChunkEnvironmentSpawnData chunkEnvironmentSpawnData = chunkSpawnDataComponent.getEnvironmentSpawnData(environmentIndex);
                double spawnCount = chunkSpawnedNPCDataComponent.getEnvironmentSpawnCount(environmentIndex);
                fullyPopulated = fullyPopulated && chunkEnvironmentSpawnData.isFullyPopulated(spawnCount);
                if (!chunkEnvironmentSpawnData.isRoleSpawnable(roleIndex)) continue;
                spawnable = true;
                weight += store.getComponent(chunkRef2, this.spawnJobDataComponentType) == null && !getAndUpdateSpawnCooldown(chunkSpawnDataComponent) ? chunkEnvironmentSpawnData.getWeight(spawnCount) : 0.0;
            }
        }
        spawnData.setFullyPopulated(fullyPopulated);
        if (!spawnable) {
            stat.setUnspawnable(true);
            boolean unspawnable = true;
            for (WorldNPCSpawnStat npcStat : spawnData.getNpcStatMap().values()) {
                if (npcStat.isUnspawnable()) continue;
                unspawnable = false;
                break;
            }
            spawnData.setUnspawnable(unspawnable);
            worldSpawnData.updateSpawnability();
            return null;
        }

        // FIX: null guards in all three lambda functions (filter predicate + two weight functions)
        return RandomExtra.randomWeightedElement(chunkRefSet, (chunkRef, index) -> {
            ChunkSpawnData chunkSpawnDataComponent = store.getComponent(chunkRef, this.chunkSpawnDataComponentType);
            if (chunkSpawnDataComponent == null) return false;
            ChunkEnvironmentSpawnData chunkEnvironmentSpawnData = chunkSpawnDataComponent.getEnvironmentSpawnData(environmentIndex);
            return chunkEnvironmentSpawnData.isRoleSpawnable(index);
        }, wasFullyPopulated ? (chunkRef, index) -> {
            ChunkSpawnData spawnChunkDataComponent = store.getComponent(chunkRef, this.chunkSpawnDataComponentType);
            if (spawnChunkDataComponent == null) return 0.0;
            return store.getComponent(chunkRef, this.spawnJobDataComponentType) == null && !spawnChunkDataComponent.isOnSpawnCooldown() ? 1.0 : 0.0;
        } : (chunkRef, index) -> {
            ChunkSpawnData chunkSpawnDataComponent = store.getComponent(chunkRef, this.chunkSpawnDataComponentType);
            if (chunkSpawnDataComponent == null) return 0.0;
            ChunkSpawnedNPCData chunkSpawnedNPCDataComponent = store.getComponent(chunkRef, this.chunkSpawnedNPCDataComponentType);
            if (chunkSpawnedNPCDataComponent == null) return 0.0;
            ChunkEnvironmentSpawnData chunkEnvironmentSpawnData = chunkSpawnDataComponent.getEnvironmentSpawnData(environmentIndex);
            if (store.getComponent(chunkRef, this.spawnJobDataComponentType) == null && !chunkSpawnDataComponent.isOnSpawnCooldown()) {
                return chunkEnvironmentSpawnData.getWeight(chunkSpawnedNPCDataComponent.getEnvironmentSpawnCount(environmentIndex));
            }
            return 0.0;
        }, weight, roleIndex);
    }
}
