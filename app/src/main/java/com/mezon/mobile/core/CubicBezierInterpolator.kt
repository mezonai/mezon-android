package com.mezon.mobile.core

import android.graphics.PointF
import android.view.animation.Interpolator


class CubicBezierInterpolator : Interpolator {

    companion object {
        @JvmField val DEFAULT = CubicBezierInterpolator(0.25, 0.1, 0.25, 1.0)
        @JvmField val EASE_OUT = CubicBezierInterpolator(0.0, 0.0, 0.58, 1.0)
        @JvmField val EASE_OUT_QUINT = CubicBezierInterpolator(0.23, 1.0, 0.32, 1.0)
        @JvmField val EASE_IN = CubicBezierInterpolator(0.42, 0.0, 1.0, 1.0)
        @JvmField val EASE_BOTH = CubicBezierInterpolator(0.42, 0.0, 0.58, 1.0)
        @JvmField val EASE_OUT_BACK = CubicBezierInterpolator(0.34, 1.56, 0.64, 1.0)
    }

    private val start: PointF
    private val end: PointF
    private val a = PointF()
    private val b = PointF()
    private val c = PointF()

    constructor(start: PointF, end: PointF) {
        require(start.x in 0f..1f) { "startX value must be in the range [0, 1]" }
        require(end.x in 0f..1f) { "endX value must be in the range [0, 1]" }
        this.start = start
        this.end = end
    }

    constructor(startX: Float, startY: Float, endX: Float, endY: Float)
            : this(PointF(startX, startY), PointF(endX, endY))

    constructor(startX: Double, startY: Double, endX: Double, endY: Double)
            : this(startX.toFloat(), startY.toFloat(), endX.toFloat(), endY.toFloat())

    override fun getInterpolation(input: Float): Float {
        return getBezierCoordinateY(getXForTime(input))
    }

    private fun getBezierCoordinateY(time: Float): Float {
        c.y = 3 * start.y
        b.y = 3 * (end.y - start.y) - c.y
        a.y = 1 - c.y - b.y
        return time * (c.y + time * (b.y + time * a.y))
    }

    private fun getXForTime(time: Float): Float {
        var x = time
        for (i in 1..13) {
            val z = getBezierCoordinateX(x) - time
            if (Math.abs(z) < 1e-3f) break
            x -= z / getXDerivative(x)
        }
        return x
    }

    private fun getXDerivative(t: Float): Float {
        return c.x + t * (2 * b.x + 3 * a.x * t)
    }

    private fun getBezierCoordinateX(time: Float): Float {
        c.x = 3 * start.x
        b.x = 3 * (end.x - start.x) - c.x
        a.x = 1 - c.x - b.x
        return time * (c.x + time * (b.x + time * a.x))
    }
}
