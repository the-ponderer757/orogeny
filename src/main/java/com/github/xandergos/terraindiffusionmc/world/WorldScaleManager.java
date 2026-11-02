package com.github.xandergos.terraindiffusionmc.world;

import net.minecraft.server.level.ServerLevel;

/**
 * Runtime access for world-scoped terrain scale.
 */
public final class WorldScaleManager {
    public static final int DEFAULT_SCALE = 2;
    private static final int MIN_SCALE = 1;
    public static final int MAX_SCALE = 6;

    private static volatile int currentScale = DEFAULT_SCALE;

    private WorldScaleManager() {
    }

    /**
     * Loads or creates per-world scale settings and sets the active runtime value.
     *
     * <p>If the world has no explicit stored scale yet (it is being created), this applies the scale picked on the
     * create-world screen when there is one, otherwise the config's {@code world.scale} (dedicated servers, which have
     * no create-world screen; Calvin, Nov 2). Once stored, the world keeps its scale whatever the config says later.
     */
    public static void initializeForWorld(ServerLevel serverWorld) {
        WorldScaleSettingsState worldScaleSettingsState = serverWorld.getDataStorage()
                .computeIfAbsent(WorldScaleSettingsState.TYPE);

        if (!worldScaleSettingsState.hasExplicitScale()) {
            Integer pendingScale = WorldScaleSelectionState.consumePendingScale();
            int resolvedScale = pendingScale != null ? pendingScale
                    : com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig.worldScale();
            worldScaleSettingsState.setScale(resolvedScale);
        }

        currentScale = clampScale(worldScaleSettingsState.getScale());
        org.slf4j.LoggerFactory.getLogger(WorldScaleManager.class).info(
                "Orogeny: world scale {} (one block is {} m)", currentScale, 30f / currentScale);
    }

    /**
     * Returns the currently active world scale.
     */
    public static int getCurrentScale() {
        return currentScale;
    }

    /**
     * Updates world scale for the currently loaded world and persists it immediately.
     */
    public static void setCurrentScale(ServerLevel serverWorld, int configuredScale) {
        int clampedScale = clampScale(configuredScale);
        WorldScaleSettingsState worldScaleSettingsState = serverWorld.getDataStorage()
                .computeIfAbsent(WorldScaleSettingsState.TYPE);
        worldScaleSettingsState.setScale(clampedScale);
        currentScale = clampedScale;
    }

    /**
     * Clamps world scale to supported runtime bounds.
     */
    public static int clampScale(int configuredScale) {
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, configuredScale));
    }
}
