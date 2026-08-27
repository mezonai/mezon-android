package com.mezon.mobile.home.chat

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.mezon.mobile.R
import com.mezon.mobile.core.InAppOverlayHost
import com.mezon.mobile.core.LayoutHelper

class CameraPhotoReviewDialog(
    context: Context,
    capture: CameraPhotoCapture,
    private val onRetake: () -> Boolean,
    private val onUsePhoto: () -> Unit,
    private val onCancelReview: () -> Unit
) : Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {

    private var actionHandled = false
    private var imageRequest: MezonImageLoader.Cancellable? = null
    private val rootView: FrameLayout

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window?.apply {
            setWindowAnimations(0)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.BLACK))
            statusBarColor = Color.BLACK
            navigationBarColor = Color.BLACK
            addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        }

        rootView = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
        val photo = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
            contentDescription = context.getString(R.string.camera_photo_preview)
        }
        rootView.addView(
            photo,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply { bottomMargin = LayoutHelper.dp(88f) }
        )

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(LayoutHelper.dp(16f), 0, LayoutHelper.dp(16f), 0)
            setBackgroundColor(0xFF111111.toInt())
        }
        actions.addView(actionButton(context.getString(R.string.camera_retake)) {
            handleRetake()
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        actions.addView(actionButton(context.getString(R.string.camera_use_photo), Gravity.END) {
            handleAction(onUsePhoto)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        rootView.addView(
            actions,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, LayoutHelper.dp(88f), Gravity.BOTTOM)
        )
        setContentView(rootView)

        val size = context.resources.displayMetrics.run { maxOf(widthPixels, heightPixels) }
        imageRequest = MezonImageLoader.getInstance(context).loadFromUri(
            capture.uri,
            size,
            size,
            onSuccess = { photo.setImageBitmap(it) },
            onError = { photo.setImageURI(capture.uri) }
        )
    }

    private fun actionButton(label: String, textGravity: Int = Gravity.START, action: () -> Unit) =
        TextView(context).apply {
            text = label
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL or textGravity
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }

    private fun handleAction(action: () -> Unit) {
        if (actionHandled) return
        actionHandled = true
        action()
        rootView.animate()
            .alpha(0f)
            .setDuration(USE_PHOTO_FADE_MS)
            .withEndAction { dismiss() }
            .start()
    }

    private fun handleRetake() {
        if (actionHandled) return
        if (onRetake()) actionHandled = true
    }

    override fun cancel() {
        if (!actionHandled) {
            actionHandled = true
            onCancelReview()
        }
        super.cancel()
    }

    override fun show() {
        super.show()
        InAppOverlayHost.register(this, dismissOnOverlayTap = false)
    }

    override fun dismiss() {
        InAppOverlayHost.unregister(this)
        rootView.animate().cancel()
        imageRequest?.cancel()
        imageRequest = null
        super.dismiss()
    }

    companion object {
        private const val USE_PHOTO_FADE_MS = 120L
    }
}
