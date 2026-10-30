package cam.su.kernel.ui.screen.home.arena.three

import com.google.android.filament.Engine

/**
 * The slime materials, ours and gltfio's ubershaders, only have packages for feature level 1 and
 * up. On a level 0 engine (GLES 2.0) building them is a native abort that no catch can stop, so
 * refuse here: createOrNull then returns null and the status card keeps its 2D backdrop only.
 */
internal fun createSlimeEngine(): Engine {
    val engine = Engine.create()
    if (engine.activeFeatureLevel == Engine.FeatureLevel.FEATURE_LEVEL_0) {
        engine.destroy()
        throw UnsupportedOperationException("GLES 2.0: no slime materials for this feature level")
    }
    return engine
}
