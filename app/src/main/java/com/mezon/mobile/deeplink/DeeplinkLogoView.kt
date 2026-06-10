package com.mezon.mobile.deeplink

import android.content.Context
import android.graphics.Canvas
import android.view.View
import com.mezon.mobile.core.AvatarDrawable
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.chat.MezonImageLoader
import com.mezon.mobile.util.avatarImgproxyUrl

class DeeplinkLogoView(
    context: Context,
    private val theme: ThemeColors,
    private val sizeDp: Int,
    private val cornerRadiusDp: Float,
) : View(context) {

    enum class FallbackStyle {
        CLAN_LIST,
        BLURPLE,
        AVATAR_DEFAULT,
    }

    private val avatarDrawable = AvatarDrawable()
    private var currentUrl: String? = null
    private var cancellable: MezonImageLoader.Cancellable? = null
    private var attachedToWindow = false

    init {
        avatarDrawable.cornerRadius = LayoutHelper.dp(cornerRadiusDp).toFloat()
    }

    fun bind(
        fallbackKey: Long,
        displayName: String,
        logoUrl: String?,
        fallbackStyle: FallbackStyle = FallbackStyle.CLAN_LIST,
    ) {
        avatarDrawable.setInfo(fallbackKey, displayName)
        when (fallbackStyle) {
            FallbackStyle.CLAN_LIST -> Unit
            FallbackStyle.BLURPLE -> avatarDrawable.setColor(theme.blurple)
            FallbackStyle.AVATAR_DEFAULT -> avatarDrawable.setColor(theme.colorAvatarDefault)
        }
        setImageUrl(logoUrl)
    }

    fun cancelLoad() {
        cancellable?.cancel()
        cancellable = null
    }

    private fun setImageUrl(url: String?) {
        if (url == currentUrl && (cancellable != null || avatarDrawable.hasPhoto())) return
        currentUrl = url
        cancellable?.cancel()
        cancellable = null
        if (url.isNullOrBlank()) {
            avatarDrawable.setPhoto(null)
            invalidate()
            return
        }
        if (attachedToWindow) {
            loadImage(url)
        }
    }

    private fun loadImage(url: String) {
        val sizePx = LayoutHelper.dp(sizeDp)
        val proxyUrl = avatarImgproxyUrl(url, sizePx)
        cancellable = MezonImageLoader.getInstance(context).load(
            proxyUrl,
            sizePx,
            sizePx,
            onSuccess = { bmp ->
                cancellable = null
                avatarDrawable.setPhoto(bmp)
                invalidate()
            },
            onError = {
                cancellable = null
                avatarDrawable.setPhoto(null)
                invalidate()
            }
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attachedToWindow = true
        val url = currentUrl
        if (!url.isNullOrBlank() && cancellable == null && !avatarDrawable.hasPhoto()) {
            loadImage(url)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        attachedToWindow = false
        cancelLoad()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = LayoutHelper.dp(sizeDp)
        setMeasuredDimension(size, size)
    }

    override fun hasOverlappingRendering(): Boolean = false

    override fun onDraw(canvas: Canvas) {
        avatarDrawable.setBounds(0, 0, width, height)
        avatarDrawable.draw(canvas)
    }
}
