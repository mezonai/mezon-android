package com.mezon.mobile.auth

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.ui.cells.ActionButton

internal object AuthStyle {
    val background = 0xFFDDD9E2.toInt()
    val statusBar = 0xFF7857CC.toInt()
    val secondaryText = 0xCCFFFFFF.toInt()
    val linkText = 0xD6FFFFFF.toInt()
    val cooldownText = 0xFFFFF7D6.toInt()
    val button = 0xFF383FB5.toInt()
    val disabledButton = 0xFFA0A7ED.toInt()
    val disabledButtonText = 0xD9FFFFFF.toInt()
    val welcomeButton = 0xFF5058D2.toInt()

    fun styleButton(button: ActionButton) {
        button.cornerRadiusDp = 25f
        button.gradientStartColor = this.button
        button.gradientEndColor = this.button
        button.disabledColor = disabledButton
        button.disabledTextColor = disabledButtonText
        button.useGradient = true
    }
}

abstract class AuthFragment : BaseFragment() {
    override fun getStatusBarColor(): Int = AuthStyle.statusBar
    override fun isLightStatusBar(): Boolean = false
    override fun getNavigationBarColor(): Int = AuthStyle.background

    override fun onResume() {
        super.onResume()
        checkSystemBarColors()
    }
}

internal class AuthBackground(context: Context) : FrameLayout(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var content: LinearLayout? = null

    init {
        setBackgroundColor(AuthStyle.background)
        setWillNotDraw(false)
    }

    fun followContent(form: LinearLayout) {
        content = form
        form.viewTreeObserver.addOnGlobalLayoutListener { invalidate() }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val form = content
        val lastVisible = form?.let { layout ->
            (layout.childCount - 1 downTo 0)
                .map { layout.getChildAt(it) }
                .firstOrNull { it.visibility != View.GONE }
        }
        val contentBottom = lastVisible?.bottom?.toFloat() ?: 0f
        val gradientHeight = maxOf(height * 0.82f, (contentBottom + LayoutHelper.dp(45)) / 0.78f)
        val w = width.toFloat()
        if (w <= 0f || gradientHeight <= 0f) return

        path.reset()
        path.moveTo(0f, 0f)
        path.lineTo(w, 0f)
        path.lineTo(w, gradientHeight * 0.78f)
        path.quadTo(w * 0.62f, gradientHeight * 1.04f, 0f, gradientHeight * 0.94f)
        path.close()
        paint.color = Color.WHITE
        paint.shader = LinearGradient(
            0f, 0f, 0f, gradientHeight,
            intArrayOf(0xFF7857CC.toInt(), 0xFF7462DE.toInt(), 0xFF5560E8.toInt(), 0xFF4752E5.toInt()),
            floatArrayOf(0f, 0.22f, 0.65f, 1f), Shader.TileMode.CLAMP
        )
        paint.style = Paint.Style.FILL
        canvas.drawPath(path, paint)
        paint.shader = null

        canvas.save()
        canvas.clipPath(path)
        val diameter = LayoutHelper.dpf(150f)
        val radius = diameter / 2f
        val leftX = LayoutHelper.dpf(-70f) + radius
        val topY = LayoutHelper.dpf(25f) + radius
        val rightX = w - LayoutHelper.dpf(85f) + radius
        val rightY = gradientHeight * 0.60f + radius
        paint.color = Color.argb(15, 255, 255, 255)
        canvas.drawCircle(leftX, topY, radius, paint)
        canvas.drawCircle(rightX, rightY, radius, paint)
        paint.color = Color.argb(31, 255, 255, 255)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = LayoutHelper.dpf(1f)
        canvas.drawCircle(leftX, topY, radius, paint)
        canvas.drawCircle(rightX, rightY, radius, paint)
        paint.style = Paint.Style.FILL
        canvas.restore()
    }
}
