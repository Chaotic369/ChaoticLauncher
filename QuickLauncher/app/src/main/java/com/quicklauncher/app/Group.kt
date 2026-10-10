package com.quicklauncher.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Expanded App Group: the popup card turns into a scrollable grid of the group's apps (any number).
 * Hover mode (finger still down): slide over an app and release to launch it; sliding to the top/bottom edge scrolls.
 * Touch mode: tap an app to launch it, drag to scroll, the header chevron goes back to the main grid.
 */
class GroupView(ctx: Context, private val mgr: OverlayManager) : View(ctx) {
    var onBack: () -> Unit = {}
    var onLaunch: (String) -> Unit = {}
    /** False while the finger that opened the panel is still down (hover mode); true once it is a tap/scroll panel. */
    var touchMode = false

    private class Cell(val pkg: String, val label: String, val item: Item) { var text: CharSequence = label }

    private val d = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val headerH = 46 * d
    private val pad = 10 * d

    private var title = ""
    private var cells: List<Cell> = emptyList()
    private var scroll = 0f
    private var hover = -1
    private var hoverHdr = 0
    private var armed = true
    private var openX = 0f
    private var openY = 0f
    private var lastX = -1f
    private var lastY = -1f
    private var autoDir = 0
    private var tMode = 0
    private var downX = 0f
    private var downY = 0f
    private var lastTY = 0f

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val rf = RectF()
    private val clip = Path()
    private val inv: () -> Unit = { invalidate() }

    private val auto = object : Runnable {
        override fun run() {
            if (autoDir == 0) return
            val before = scroll
            scroll += autoDir * 16 * d
            clampScroll()
            if (scroll == before) { autoDir = 0; return }
            if (armed && lastY > headerH) setHover(0, cellAt(lastX, lastY))
            invalidate()
            postOnAnimation(this)
        }
    }

    // ───── layout maths (px) ─────
    private fun cols() = mgr.popupSettings.cols.coerceIn(3, 6)
    private fun cellW() = (width - 2 * pad) / cols()
    private fun iconPx() = min(mgr.popupSettings.icon * d, cellW() * 0.82f)
    private fun cellH() = iconPx() + 26 * d
    private fun rowsN() = (cells.size + cols() - 1) / cols()
    private fun contentH() = rowsN() * cellH() + 2 * pad
    private fun maxScroll() = max(0f, contentH() - (height - headerH))
    private fun clampScroll() { scroll = scroll.coerceIn(0f, maxScroll()) }

    private fun cellAt(x: Float, y: Float): Int {
        if (y < headerH || x < pad || x > width - pad) return -1
        val yy = y - headerH - pad + scroll
        if (yy < 0f) return -1
        val col = ((x - pad) / cellW()).toInt()
        val row = (yy / cellH()).toInt()
        val i = row * cols() + col
        return if (col in 0 until cols() && i in cells.indices) i else -1
    }

    private fun hdrAt(x: Float): Int = if (x < pad + 40 * d) 1 else 0

    private fun layoutLabels() {
        if (width <= 0) return
        tp.textSize = mgr.popupSettings.lbl * d
        val w = cellW() - 4 * d
        cells.forEach { it.text = TextUtils.ellipsize(it.label, tp, w, TextUtils.TruncateAt.END) }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { layoutLabels(); clampScroll() }

    // ───── lifecycle ─────
    fun open(it: Item, gesture: Boolean, px: Float, py: Float) {
        title = it.n
        val labels = Apps.list(context).associate { a -> a.pkg to a.label }
        cells = it.members.mapNotNull { pkg ->
            labels[pkg]?.let { l -> Cell(pkg, l, Item("m:$pkg", "app", l, pkg, "app")) }   // uninstalled apps are skipped
        }
        scroll = 0f; hover = -1; hoverHdr = 0; autoDir = 0
        // A panel that opens under the finger must not react until the finger actually moves.
        armed = !gesture; openX = px; openY = py; lastX = px; lastY = py
        layoutLabels()
        invalidate()
    }

    fun close() { removeCallbacks(auto); autoDir = 0; cells = emptyList() }

    override fun onDetachedFromWindow() { removeCallbacks(auto); super.onDetachedFromWindow() }

    fun clearHover() { setAuto(0); hover = -1; hoverHdr = 0; invalidate() }

    /** The finger came up on the panel: from now on it behaves as a touch panel. */
    fun enterTouch() { touchMode = true }

    // ───── hover mode ─────
    fun pointer(x: Float, y: Float) {
        if (!armed) {
            if (hypot(x - openX, y - openY) < 14 * d) return
            armed = true
        }
        lastX = x; lastY = y
        val inside = x >= 0f && x <= width && y >= 0f && y <= height
        var hdr = 0; var cell = -1
        if (inside) { if (y < headerH) hdr = hdrAt(x) else cell = cellAt(x, y) }
        setHover(hdr, cell)
        val edge = 34 * d
        val dir = when {
            !inside -> 0
            y > headerH && y < headerH + edge && scroll > 1f -> -1
            y > height - edge && scroll < maxScroll() - 1f -> 1
            else -> 0
        }
        setAuto(dir)
        invalidate()
    }

    private fun setAuto(dir: Int) {
        if (dir == autoDir) return
        autoDir = dir
        if (dir != 0) postOnAnimation(auto)
        invalidate()
    }

    private fun setHover(hdr: Int, cell: Int) {
        if (hdr == hoverHdr && cell == hover) return
        hoverHdr = hdr; hover = cell
        if (hdr != 0 || cell >= 0) mgr.haptic(true)
    }

    /** Finger lifted. True when it was over the panel (the caller keeps the panel open in touch mode). */
    fun release(): Boolean {
        setAuto(0)
        if (!armed) return true
        val inside = lastX >= 0f && lastX <= width && lastY >= 0f && lastY <= height
        if (!inside) return false
        when {
            hoverHdr == 1 -> onBack()
            hover >= 0 -> cells.getOrNull(hover)?.let { onLaunch(it.pkg) }
        }
        return true
    }

    // ───── touch mode ─────
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; lastTY = e.y; tMode = 1
                parent?.requestDisallowInterceptTouchEvent(true)
                if (e.y >= headerH) { hover = cellAt(e.x, e.y); hoverHdr = 0 } else { hoverHdr = hdrAt(e.x); hover = -1 }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (tMode == 1 && hypot(e.x - downX, e.y - downY) > slop) { tMode = 2; hover = -1; hoverHdr = 0 }
                if (tMode == 2) { scroll -= (e.y - lastTY) * 2.2f; clampScroll(); invalidate() }
                lastTY = e.y
            }
            MotionEvent.ACTION_UP -> {
                val wasTap = tMode == 1
                tMode = 0; hover = -1; hoverHdr = 0
                if (wasTap) tap(e.x, e.y)
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { tMode = 0; hover = -1; hoverHdr = 0; invalidate() }
        }
        return true
    }

    private fun tap(x: Float, y: Float) {
        if (y < headerH) { if (hdrAt(x) == 1) onBack(); return }
        val i = cellAt(x, y)
        if (i >= 0) { mgr.haptic(true); onLaunch(cells[i].pkg) }
    }

    // ───── drawing ─────
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val s = mgr.popupSettings
        val rad = s.popRad * d
        rf.set(0f, 0f, w, h)
        bg.color = Color.argb((max(s.glass, 90f) / 100f * 255).toInt().coerceAtMost(255), 0, 0, 0)
        c.drawRoundRect(rf, rad, rad, bg)
        c.save()
        clip.reset(); clip.addRoundRect(rf, rad, rad, Path.Direction.CW); c.clipPath(clip)
        val solid = Fa.typeface(context, false)

        c.save()
        c.clipRect(0f, headerH, w, h)
        if (cells.isEmpty()) {
            tp.textAlign = Paint.Align.CENTER; tp.textSize = 12f * d; tp.color = 0x8CC8C8C8.toInt()
            c.drawText("No installed apps in this group", w / 2, headerH + 60 * d, tp)
        } else drawGrid(c, h, s)
        c.restore()

        if (scroll > 1f) drawArrow(c, "\uf077", w / 2, headerH + 14 * d, autoDir == -1, solid)
        if (scroll < maxScroll() - 1f) drawArrow(c, "\uf078", w / 2, h - 14 * d, autoDir == 1, solid)
        drawHeader(c, w, solid)
        c.restore()
    }

    private fun drawGrid(c: Canvas, h: Float, s: Settings) {
        val cols = cols(); val cw = cellW(); val ch = cellH(); val ip = iconPx()
        val rad = ip * s.effRadius / 100f
        tp.textAlign = Paint.Align.CENTER; tp.textSize = s.lbl * d
        for (i in cells.indices) {
            if (i == hover) continue
            val top = headerH + pad + (i / cols) * ch - scroll
            if (top > h || top + ch < headerH) continue
            drawCell(c, cells[i], pad + (i % cols) * cw + cw / 2, top, ip, rad, false, s.showLbl)
        }
        val hv = cells.getOrNull(hover)
        if (hv != null) {
            val top = headerH + pad + (hover / cols) * ch - scroll
            drawCell(c, hv, pad + (hover % cols) * cw + cw / 2, top, ip, rad, true, s.showLbl)
        }
    }

    private fun drawCell(c: Canvas, cell: Cell, cx: Float, top: Float, ip: Float, rad: Float, hov: Boolean, showLbl: Boolean) {
        c.save()
        if (hov) c.scale(1.25f, 1.25f, cx, top + ip / 2)
        rf.set(cx - ip / 2, top, cx + ip / 2, top + ip)
        if (hov) {
            p.reset(); p.isAntiAlias = true; p.color = 0xFF000000.toInt(); p.setShadowLayer(10 * d, 0f, 5 * d, 0x99000000.toInt())
            c.drawRoundRect(rf, rad, rad, p); p.clearShadowLayer()
        }
        val bmp = Icons.peek(cell.item, context, inv)
        p.reset(); p.isAntiAlias = true
        if (bmp != null) {
            c.save(); clip.reset(); clip.addRoundRect(rf, rad, rad, Path.Direction.CW); c.clipPath(clip)
            p.isFilterBitmap = true
            c.drawBitmap(bmp, null, rf, p)
            c.restore()
        } else {
            p.color = 0x22FFFFFF
            c.drawRoundRect(rf, rad, rad, p)
        }
        c.restore()
        if (showLbl) {
            tp.color = if (hov) Color.WHITE else 0xE6F6EFE4.toInt()
            val t = cell.text
            c.drawText(t, 0, t.length, cx, top + ip + 6 * d - tp.ascent(), tp)
        }
    }

    private fun drawArrow(c: Canvas, glyph: String, cx: Float, cy: Float, active: Boolean, solid: Typeface) {
        gp.typeface = solid; gp.textSize = 15 * d
        gp.color = if (active) ACCENT else Color.WHITE
        gp.setShadowLayer(3 * d, 0f, 1.5f * d, 0xD9000000.toInt())
        c.drawText(glyph, cx, cy - (gp.ascent() + gp.descent()) / 2, gp)
        gp.clearShadowLayer()
    }

    private fun drawHeader(c: Canvas, w: Float, solid: Typeface) {
        p.reset(); p.isAntiAlias = true
        p.color = 0xF8080808.toInt()
        c.drawRect(0f, 0f, w, headerH, p)
        p.color = 0x12FFFFFF
        c.drawRect(0f, headerH - d, w, headerH, p)
        val bs = 30 * d; val px = 10 * d
        rf.set(px, (headerH - bs) / 2, px + bs, (headerH + bs) / 2)
        p.reset(); p.isAntiAlias = true
        p.color = if (hoverHdr == 1) 0x59B3202B else 0x14FFFFFF
        c.drawRoundRect(rf, 10 * d, 10 * d, p)
        gp.typeface = solid; gp.textSize = 12 * d
        gp.color = if (hoverHdr == 1) ACCENT else 0xFFECECEC.toInt()
        c.drawText("\uf053", rf.centerX(), rf.centerY() - (gp.ascent() + gp.descent()) / 2, gp)

        val tx = px + bs + 10 * d; val tw = w - tx - px
        tp.textAlign = Paint.Align.LEFT
        tp.textSize = 12.5f * d; tp.color = 0xFFECECEC.toInt(); tp.isFakeBoldText = true
        val t1 = TextUtils.ellipsize(title, tp, tw, TextUtils.TruncateAt.END)
        c.drawText(t1, 0, t1.length, tx, headerH / 2 - 2 * d, tp)
        tp.isFakeBoldText = false
        tp.textSize = 9.5f * d; tp.color = 0x8CC8C8C8.toInt()
        val sub = "${cells.size} app" + (if (cells.size == 1) "" else "s")
        c.drawText(sub, 0, sub.length, tx, headerH / 2 + 11 * d, tp)
        tp.textAlign = Paint.Align.CENTER
    }
}
