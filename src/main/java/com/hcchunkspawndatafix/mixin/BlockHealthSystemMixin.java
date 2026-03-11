package com.hcchunkspawndatafix.mixin;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.ResourceType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.BlockPosition;
import com.hypixel.hytale.protocol.packets.world.UpdateBlockDamage;
import com.hypixel.hytale.server.core.modules.blockhealth.BlockHealth;
import com.hypixel.hytale.server.core.modules.blockhealth.BlockHealthChunk;
import com.hypixel.hytale.server.core.modules.blockhealth.FragileBlock;
import com.hypixel.hytale.server.core.modules.entity.player.ChunkTracker;
import com.hypixel.hytale.server.core.modules.time.TimeResource;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nonnull;
import java.time.Instant;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.logging.Level;

/**
 * Mixin to fix two errors spamming thousands of times per minute in BlockHealthSystem.tick():
 *
 * 1. IndexOutOfBoundsException in getComponent() — stale entity ref for a player who
 *    disconnected/teleported between the isValid() check and the getComponent() call
 * 2. NullPointerException on chunkTrackerComponent.isLoaded() — getComponent() returned null,
 *    but vanilla code uses assert (no-op in production) instead of a null check
 *
 * Fix: replace assert with null guard, and wrap getComponent in try-catch for stale refs.
 */
@Mixin(targets = "com.hypixel.hytale.server.core.modules.blockhealth.BlockHealthModule$BlockHealthSystem")
public abstract class BlockHealthSystemMixin {

    private static final HytaleLogger LOGGER = HytaleLogger.get("HC_ChunkSpawnDataFix");

    @Shadow
    private ComponentType<ChunkStore, BlockHealthChunk> blockHealthComponentChunkType;

    @Shadow
    private ResourceType<EntityStore, TimeResource> timeResourceType;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void onInit(CallbackInfo ci) {
        LOGGER.at(Level.INFO).log("BlockHealthSystem patched with null-safety checks");
    }

    /**
     * @author HC_ChunkSpawnDataFix
     * @reason Fix NPE/IOOBE when ChunkTracker is null or ref is stale during player iteration
     */
    @Overwrite
    public void tick(float dt, int index, @Nonnull ArchetypeChunk<ChunkStore> archetypeChunk, @Nonnull Store<ChunkStore> store, @Nonnull CommandBuffer<ChunkStore> commandBuffer) {
        Map<Vector3i, BlockHealth> blockHealthMap;
        BlockHealthChunk blockHealthChunkComponent = archetypeChunk.getComponent(index, this.blockHealthComponentChunkType);
        assert (blockHealthChunkComponent != null);
        World world = store.getExternalData().getWorld();
        Store<EntityStore> entityStore = world.getEntityStore().getStore();
        TimeResource uptime = world.getEntityStore().getStore().getResource(this.timeResourceType);
        Instant currentGameTime = uptime.getNow();
        Instant lastRepairGameTime = blockHealthChunkComponent.getLastRepairGameTime();
        blockHealthChunkComponent.setLastRepairGameTime(currentGameTime);
        if (lastRepairGameTime == null) {
            return;
        }
        Map<Vector3i, FragileBlock> blockFragilityMap = blockHealthChunkComponent.getBlockFragilityMap();
        if (!blockFragilityMap.isEmpty()) {
            float deltaSeconds = (float)(currentGameTime.toEpochMilli() - lastRepairGameTime.toEpochMilli()) / 1000.0f;
            Iterator<Map.Entry<Vector3i, FragileBlock>> iterator = blockFragilityMap.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Vector3i, FragileBlock> entry = iterator.next();
                FragileBlock fragileBlock = entry.getValue();
                float newDuration = fragileBlock.getDurationSeconds() - deltaSeconds;
                if (newDuration <= 0.0f) {
                    iterator.remove();
                    continue;
                }
                fragileBlock.setDurationSeconds(newDuration);
            }
        }
        if ((blockHealthMap = blockHealthChunkComponent.getBlockHealthMap()).isEmpty()) {
            return;
        }
        WorldChunk chunk = archetypeChunk.getComponent(index, WorldChunk.getComponentType());
        assert (chunk != null);
        Collection<PlayerRef> allPlayers = world.getPlayerRefs();
        ObjectArrayList<PlayerRef> visibleTo = new ObjectArrayList<PlayerRef>(allPlayers.size());
        for (PlayerRef playerRef : allPlayers) {
            Ref<EntityStore> playerReference = playerRef.getReference();
            if (playerReference == null || !playerReference.isValid()) continue;
            ChunkTracker chunkTrackerComponent;
            try {
                chunkTrackerComponent = entityStore.getComponent(playerReference, ChunkTracker.getComponentType());
            } catch (Exception e) {
                continue; // Stale ref — player disconnected between isValid() and getComponent()
            }
            if (chunkTrackerComponent == null) continue; // Player mid-transition, skip
            if (!chunkTrackerComponent.isLoaded(chunk.getIndex())) continue;
            visibleTo.add(playerRef);
        }
        float deltaSeconds = (float)(currentGameTime.toEpochMilli() - lastRepairGameTime.toEpochMilli()) / 1000.0f;
        Iterator<Map.Entry<Vector3i, BlockHealth>> iterator = blockHealthMap.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Vector3i, BlockHealth> entry = iterator.next();
            Vector3i position = entry.getKey();
            BlockHealth blockHealth = entry.getValue();
            Instant startRegenerating = blockHealth.getLastDamageGameTime().plusSeconds(5L);
            if (currentGameTime.isBefore(startRegenerating)) continue;
            float healthDelta = 0.1f * deltaSeconds;
            float health = blockHealth.getHealth() + healthDelta;
            if (health < 1.0f) {
                blockHealth.setHealth(health);
            } else {
                iterator.remove();
                health = BlockHealth.NO_DAMAGE_INSTANCE.getHealth();
                healthDelta = health - blockHealth.getHealth();
            }
            UpdateBlockDamage packet = new UpdateBlockDamage(new BlockPosition(position.getX(), position.getY(), position.getZ()), health, healthDelta);
            for (int i = 0; i < visibleTo.size(); ++i) {
                ((PlayerRef)visibleTo.get(i)).getPacketHandler().writeNoCache(packet);
            }
        }
    }
}
