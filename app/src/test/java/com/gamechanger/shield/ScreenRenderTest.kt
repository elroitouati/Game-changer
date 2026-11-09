package com.gamechanger.shield

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the main screen to PNG files (build/screens) so the design can be reviewed
 * without a device. Also checks the screen builds without crashing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenRenderTest {

    private fun capture(name: String, setup: (MainActivity) -> Unit = {}) {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        setup(activity)
        controller.pause().resume()
        val scroll = activity.window.decorView.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ScrollView
        val content = scroll.getChildAt(0)
        val w = activity.resources.displayMetrics.widthPixels
        content.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        content.layout(0, 0, content.measuredWidth, content.measuredHeight)
        val bmp = Bitmap.createBitmap(content.measuredWidth, content.measuredHeight, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(T.BG)
        content.draw(c)
        val out = File("build/screens").apply { mkdirs() }
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun firstRun() = capture("first_run")

    @Test
    fun lockedWithLevel3() = capture("locked_level3") { a ->
        Lock(a).setPassword("1234", 60)
        Lock(a).requestDelayedUnlock()
        a.getSharedPreferences("shield_level", 0).edit().putInt("level", 3)
            .putLong("since", System.currentTimeMillis() - 12L * 86_400_000L).apply()
    }
}
