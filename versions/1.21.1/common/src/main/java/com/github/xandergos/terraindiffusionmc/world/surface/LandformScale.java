package com.github.xandergos.terraindiffusionmc.world.surface;

import com.github.xandergos.terraindiffusionmc.world.WorldScaleManager;

/**
 * Surface-landmark size in world blocks relative to the neural terrain.
 * Scale 2 is the reference; large worlds get larger, less frequent formations
 * rather than identical little ornaments placed on enlarged mountains.
 */
public final class LandformScale {
    private LandformScale() {}

    public static float multiplier() {
        return Math.max(0.72f, Math.min(1.80f,
                (float) Math.sqrt(WorldScaleManager.getCurrentScale() / 2.0f)));
    }

    public static int blocks(int reference) {
        return Math.max(1, Math.round(reference * multiplier()));
    }
}
