package com.noahwallet.ui.settings

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.Window
import android.view.animation.PathInterpolator
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.window.DialogWindowProvider
import kotlin.math.hypot

/**
 * The Appearance sheet's circle reveal (appearance.md, Picking). A picture of Noah as it is (the activity's window and
 * the sheet's window above it) covers the screen, the theme changes underneath, and once the new theme shows, a circle
 * growing from the tile uncovers it. The picture is a view overlay on the sheet's own layout (an overlay on its window's
 * decor would draw under that elevated layout), so it takes no touches and the app underneath stays usable. One reveal
 * at a time; a pick while one runs is ignored.
 */
internal class AppearanceReveal {
    private var cover: Cover? = null
    private var animator: ValueAnimator? = null
    /** The change to make once the picture is up; cancelling makes it at once rather than lose the pick. */
    private var pending: (() -> Unit)? = null
    /** What runs once the reveal is over, however it ends. */
    private var done: (() -> Unit)? = null
    /** Waiting for Noah to show the other theme ([shown]); dark at the start. */
    private var waitingFrom: Boolean? = null
    /** Where the circle grows from and whether Noah was dark, for the reveal being set up. */
    private var origin = Offset.Zero
    private var fromDark = false
    private var host: View? = null
    private val timeout = Runnable { finish() }
    val running get() = cover != null || pending != null

    /**
     * Covers the screen with a picture of [view]'s window over its activity's, makes [change], then reveals the result
     * from [origin] (in [view]'s window) once [shown] reports the other theme, then runs [done]. Without a picture,
     * [change] and [done] happen at once.
     */
    fun start(view: View, origin: Offset, shownDark: Boolean, change: () -> Unit, done: () -> Unit) {
        if (running) return
        val activity = view.context.activity()?.window
        if (Build.VERSION.SDK_INT < 26 || activity == null) { change(); done(); return }
        pending = change; this.done = done; this.origin = origin; fromDark = shownDark
        val sheet = generateSequence(view.parent) { it.parent }.filterIsInstance<DialogWindowProvider>().firstOrNull()
        capture(sheet?.window ?: activity, sheet as? View ?: activity.decorView, activity)
    }

    @RequiresApi(26)
    private fun capture(top: Window, layout: View, activity: Window) = copy(activity) { below ->
        if (top === activity) cover(activity, top, layout, below, null)
        else copy(top) { above -> cover(activity, top, layout, below, above) }
    }

    /** Noah now shows [dark]: starts the circle if that's the turn a reveal is waiting for. */
    fun shown(dark: Boolean) {
        val from = waitingFrom ?: return
        if (dark == from) return
        waitingFrom = null
        val cover = cover ?: return
        host?.removeCallbacks(timeout)
        animator = ValueAnimator.ofFloat(0f, cover.farthest).apply {
            duration = 560
            interpolator = PathInterpolator(.3f, .7f, .2f, 1f)
            addUpdateListener { cover.radius = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(animation: Animator) = finish() })
            start()
        }
    }

    /** Ends any reveal now (the sheet closed); a pick still waiting for its picture is made. */
    fun cancel() = finish()

    private fun finish() {
        pending?.let { pending = null; it() }
        waitingFrom = null
        animator?.let { animator = null; it.removeAllListeners(); it.cancel() }
        host?.let { it.removeCallbacks(timeout); cover?.let(it.overlay::remove) }
        host = null
        cover?.picture?.recycle()
        cover = null
        done?.let { done = null; it() }
    }

    @RequiresApi(26)
    private fun copy(window: Window, done: (Bitmap?) -> Unit) {
        val decor = window.decorView
        if (decor.width == 0 || decor.height == 0 || !decor.isAttachedToWindow) return done(null)
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(window, Rect(0, 0, decor.width, decor.height), bitmap, { result ->
            if (result == PixelCopy.SUCCESS) done(bitmap) else { bitmap.recycle(); done(null) }
        }, Handler(Looper.getMainLooper()))
    }

    /**
     * Puts [below] (with [above], [top]'s window, drawn over it) over [layout], the top window's content, gives it a frame
     * to reach the screen, then makes the change.
     */
    private fun cover(activity: Window, top: Window, layout: View, below: Bitmap?, above: Bitmap?) {
        // Cancelled meanwhile (the change is already made).
        val change = pending ?: run { below?.recycle(); above?.recycle(); return }
        val sheetMissing = top !== activity && above == null
        if (below == null || sheetMissing || !layout.isAttachedToWindow) { below?.recycle(); above?.recycle(); finish(); return }
        val topAt = IntArray(2).also(top.decorView::getLocationOnScreen)
        val belowAt = IntArray(2).also(activity.decorView::getLocationOnScreen)
        val layoutAt = IntArray(2).also(layout::getLocationOnScreen)
        above?.let { Canvas(below).drawBitmap(it, (topAt[0] - belowAt[0]).toFloat(), (topAt[1] - belowAt[1]).toFloat(), null); it.recycle() }
        val cover = Cover(below, (belowAt[0] - layoutAt[0]).toFloat(), (belowAt[1] - layoutAt[1]).toFloat(),
            origin + Offset((topAt[0] - layoutAt[0]).toFloat(), (topAt[1] - layoutAt[1]).toFloat()))
        cover.setBounds(0, 0, layout.width, layout.height)
        layout.overlay.add(cover)
        this.cover = cover; host = layout
        layout.postOnAnimation { layout.postOnAnimation {
            if (this.cover !== cover || pending == null) return@postOnAnimation
            pending = null; waitingFrom = fromDark
            change()
            // A turn that never shows (the phone was already there): the picture just goes.
            layout.postDelayed(timeout, 1_000)
        } }
    }

    private tailrec fun Context.activity(): Activity? = this as? Activity ?: (this as? ContextWrapper)?.baseContext?.activity()

    /** The picture with a circular hole of [radius] at [origin], in the top window's coordinates. */
    private class Cover(val picture: Bitmap, private val left: Float, private val top: Float, private val origin: Offset) : Drawable() {
        val farthest = listOf(left to top, left + picture.width to top, left to top + picture.height,
            left + picture.width to top + picture.height).maxOf { (x, y) -> hypot(x - origin.x, y - origin.y) }
        var radius = 0f
            set(value) { field = value; invalidateSelf() }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(picture, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(Matrix().apply { setTranslate(left, top) })
            }
        }
        private val path = Path()

        override fun draw(canvas: Canvas) {
            // rewind() also resets the fill type (to winding, which would fill the circle in).
            path.rewind()
            path.fillType = Path.FillType.EVEN_ODD
            path.addRect(left, top, left + picture.width, top + picture.height, Path.Direction.CW)
            if (radius > 0f) path.addCircle(origin.x, origin.y, radius, Path.Direction.CW)
            canvas.drawPath(path, paint)
        }
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
