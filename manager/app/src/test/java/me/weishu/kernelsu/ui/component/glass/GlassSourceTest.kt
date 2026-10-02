package me.weishu.kernelsu.ui.component.glass

import org.junit.Assert.assertEquals
import org.junit.Test

class GlassSourceTest {

    @Test
    fun wallpaperDecodedShowsBitmap() =
        assertEquals(GlassSourceKind.Bitmap, pickGlassSourceKind(GlassBackgroundType.WALLPAPER, hasBitmap = true))

    @Test
    fun wallpaperUnreadableFallsBackToPlain() =
        assertEquals(GlassSourceKind.Plain, pickGlassSourceKind(GlassBackgroundType.WALLPAPER, hasBitmap = false))

    @Test
    fun gradientIgnoresBitmap() =
        assertEquals(GlassSourceKind.Gradient, pickGlassSourceKind(GlassBackgroundType.GRADIENT, hasBitmap = true))

    @Test
    fun customImageDecodedShowsBitmap() =
        assertEquals(GlassSourceKind.Bitmap, pickGlassSourceKind(GlassBackgroundType.IMAGE, hasBitmap = true))

    @Test
    fun customImageMissingFallsBackToGradient() =
        assertEquals(GlassSourceKind.Gradient, pickGlassSourceKind(GlassBackgroundType.IMAGE, hasBitmap = false))

    @Test
    fun unknownTypeBehavesLikeWallpaper() =
        assertEquals(GlassSourceKind.Plain, pickGlassSourceKind(7, hasBitmap = false))
}
