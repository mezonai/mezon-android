package com.mezon.mobile.home.voice

import android.content.Context
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import com.mezon.mobile.core.AvatarDrawable
import com.mezon.mobile.core.BottomSheet
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.chat.MezonImageLoader
import com.mezon.mobile.home.clans.VoiceMemberDisplay
import com.mezon.mobile.home.stream.JoinMediaSheetKind
import com.mezon.mobile.home.voice.sfu.SfuRole
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.util.avatarImgproxyUrl

class JoinVoiceBottomSheet(
    context: Context,
    private val themeColors: ThemeColors,
    private val channelLabel: String,
    private val channelId: Long,
    private val clanId: Long,
    private val members: List<VoiceMemberDisplay>,
    private val unreadCount: Int = 0,
    private val kind: JoinMediaSheetKind = JoinMediaSheetKind.VOICE,
) : BottomSheet(context) {

    var onJoinVoice: ((role: SfuRole) -> Unit)? = null
    var onOpenChat: (() -> Unit)? = null
    var onInvite: (() -> Unit)? = null
    private val avatarHolders = ArrayList<AvatarHolder>()
    private var selectedRole = SfuRole.SPEAKER
    private var joinButtonView: TextView? = null

    private class AvatarHolder(
        val avatarDrawable: AvatarDrawable,
        val avatarView: ImageView,
        var cancellable: MezonImageLoader.Cancellable? = null,
        var currentUrl: String? = null
    )

    init {
        setCustomView(buildContent(context))
    }

    private fun buildContent(context: Context): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(LayoutHelper.dp(10), LayoutHelper.dp(10), LayoutHelper.dp(10), LayoutHelper.dp(24))
        }

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val closeButton = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(themeColors.surfaceVariant)
            }
            isClickable = true
            isFocusable = true
            applyVoiceButtonPressFeedback()
            setOnClickListener { dismiss() }
        }
        closeButton.addView(ImageView(context).apply {
            setImageDrawable(MezonIcon.chevronDownSmallIcon.getDrawable(context).apply {
                colorFilter = PorterDuffColorFilter(themeColors.onSurface, PorterDuff.Mode.SRC_IN)
            })
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }, FrameLayout.LayoutParams(LayoutHelper.dp(20), LayoutHelper.dp(20), Gravity.CENTER))
        headerRow.addView(closeButton, LinearLayout.LayoutParams(LayoutHelper.dp(44), LayoutHelper.dp(44)))
        val titleText = TextView(context).apply {
            text = channelLabel
            setTextColor(themeColors.onSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        headerRow.addView(titleText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = LayoutHelper.dp(10)
            marginEnd = LayoutHelper.dp(10)
        })
        headerRow.addView(buildChatButton(context), LinearLayout.LayoutParams(LayoutHelper.dp(44), LayoutHelper.dp(44)))
        root.addView(headerRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val avatarContainer = FrameLayout(context).apply {
            setPadding(0, LayoutHelper.dp(24), 0, LayoutHelper.dp(8))
        }
        val avatarRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val maxDisplay = 3
        val displayMembers = members.take(maxDisplay)
        val avatarSizePx = LayoutHelper.dp(40)
        val overlapPx = LayoutHelper.dp(5)

        if (displayMembers.isNotEmpty()) {
            for ((i, member) in displayMembers.withIndex()) {
                val avatarDrawable = AvatarDrawable().apply {
                    setInfo(member.userId, member.username)
                }
                val avatarView = ImageView(context).apply {
                    setImageDrawable(avatarDrawable)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }
                val avatarWrap = FrameLayout(context).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(0xFFFFFFFF.toInt())
                    }
                    addView(avatarView, FrameLayout.LayoutParams(
                        LayoutHelper.dp(38), LayoutHelper.dp(38), Gravity.CENTER
                    ))
                }
                val holder = AvatarHolder(avatarDrawable, avatarView)
                avatarHolders.add(holder)
                loadAvatar(holder, member.avatarUrl, LayoutHelper.dp(38))
                val lp = LinearLayout.LayoutParams(avatarSizePx, avatarSizePx)
                if (i > 0) lp.marginStart = -overlapPx
                avatarRow.addView(avatarWrap, lp)
            }

            if (members.size > maxDisplay) {
                val overflowText = TextView(context).apply {
                    text = "+${members.size - maxDisplay}"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(themeColors.onSurface)
                    gravity = Gravity.CENTER
                    val bgDrawable = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(themeColors.surfaceVariant)
                        setStroke(LayoutHelper.dp(1), 0xFFFFFFFF.toInt())
                    }
                    background = bgDrawable
                }
                val lp = LinearLayout.LayoutParams(avatarSizePx, avatarSizePx)
                lp.marginStart = -overlapPx
                avatarRow.addView(overflowText, lp)
            }
        } else {
            val emptyVoice = FrameLayout(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(themeColors.surfaceVariant)
                }
                val icon = if (kind == JoinMediaSheetKind.STREAMING) {
                    MezonIcon.channelStream
                } else {
                    MezonIcon.channelVoice
                }
                addView(ImageView(context).apply {
                    setImageDrawable(icon.getDrawable(context).apply {
                        colorFilter = PorterDuffColorFilter(themeColors.onSurface, PorterDuff.Mode.SRC_IN)
                    })
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                }, FrameLayout.LayoutParams(LayoutHelper.dp(36), LayoutHelper.dp(36), Gravity.CENTER))
            }
            avatarRow.addView(emptyVoice, LinearLayout.LayoutParams(LayoutHelper.dp(76), LayoutHelper.dp(76)))
        }
        avatarContainer.addView(avatarRow, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ))
        root.addView(avatarContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val roomLabel = TextView(context).apply {
            text = if (kind == JoinMediaSheetKind.STREAMING) "Stream" else "Voice Room"
            setTextColor(themeColors.onSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        root.addView(roomLabel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
            bottomMargin = LayoutHelper.dp(4)
        })

        val statusLabel = TextView(context).apply {
            text = when {
                members.size >= 2 -> "Everyone is waiting inside"
                members.size == 1 -> "1 person is in the voice room"
                else -> "No one is in the voice room"
            }
            setTextColor(themeColors.onSurfaceVariant)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
        }
        root.addView(statusLabel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
            bottomMargin = LayoutHelper.dp(24)
        })

        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val joinControl: View = if (kind == JoinMediaSheetKind.STREAMING) {
            TextView(context).apply {
                text = "Join Stream"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(0xFFFFFFFF.toInt())
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    cornerRadius = LayoutHelper.dp(40).toFloat()
                    setColor(0xFF43B581.toInt())
                }
                setPadding(LayoutHelper.dp(32), LayoutHelper.dp(12), LayoutHelper.dp(32), LayoutHelper.dp(12))
                isClickable = true
                isFocusable = true
                applyVoiceButtonPressFeedback()
                setOnClickListener {
                    onJoinVoice?.invoke(selectedRole)
                    dismissWithoutAnimation()
                }
            }
        } else {
            buildSplitJoinButton(context)
        }
        buttonRow.addView(joinControl, LinearLayout.LayoutParams(
            LayoutHelper.dp(220), LayoutHelper.dp(50)
        ))

        root.addView(buttonRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        return root
    }

    private fun buildChatButton(context: Context): View {
        val chatButton = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(themeColors.surfaceVariant)
            }
            isClickable = true
            isFocusable = true
            applyVoiceButtonPressFeedback()
            setOnClickListener {
                onOpenChat?.invoke()
                dismiss()
            }
        }
        chatButton.addView(ImageView(context).apply {
            setImageDrawable(MezonIcon.chatIcon.getDrawable(context).apply {
                colorFilter = PorterDuffColorFilter(themeColors.onSurface, PorterDuff.Mode.SRC_IN)
            })
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }, FrameLayout.LayoutParams(LayoutHelper.dp(22), LayoutHelper.dp(22), Gravity.CENTER))
        if (unreadCount > 0) {
            chatButton.addView(TextView(context).apply {
                text = if (unreadCount > 99) "99+" else unreadCount.toString()
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(0xFFFFFFFF.toInt())
                gravity = Gravity.CENTER
                minWidth = LayoutHelper.dp(18)
                minHeight = LayoutHelper.dp(18)
                setPadding(LayoutHelper.dp(4), 0, LayoutHelper.dp(4), 0)
                background = GradientDrawable().apply {
                    cornerRadius = LayoutHelper.dp(9).toFloat()
                    setColor(themeColors.badgeRed)
                }
            }, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, LayoutHelper.dp(18),
                Gravity.TOP or Gravity.END
            ).apply {
                topMargin = -LayoutHelper.dp(2)
                marginEnd = -LayoutHelper.dp(2)
            })
        }
        return chatButton
    }

    private fun buildSplitJoinButton(context: Context): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(40).toFloat()
                setColor(0xFF43B581.toInt())
            }
        }
        val primary = TextView(context).apply {
            text = "Join as Speaker"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            applyVoiceButtonPressFeedback()
            setOnClickListener {
                onJoinVoice?.invoke(selectedRole)
                dismissWithoutAnimation()
            }
        }
        joinButtonView = primary
        val divider = View(context).apply { setBackgroundColor(0x33000000) }
        val dropdown = FrameLayout(context).apply {
            isClickable = true
            isFocusable = true
            applyVoiceButtonPressFeedback()
            addView(ImageView(context).apply {
                setImageDrawable(MezonIcon.chevronDownSmallIcon.getDrawable(context).apply {
                    colorFilter = PorterDuffColorFilter(0xFFFFFFFF.toInt(), PorterDuff.Mode.SRC_IN)
                })
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }, FrameLayout.LayoutParams(LayoutHelper.dp(18), LayoutHelper.dp(18), Gravity.CENTER))
            setOnClickListener { showRoleDropdown(this) }
        }
        container.addView(primary, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        container.addView(divider, LinearLayout.LayoutParams(LayoutHelper.dp(1), LinearLayout.LayoutParams.MATCH_PARENT).apply {
            topMargin = LayoutHelper.dp(10)
            bottomMargin = LayoutHelper.dp(10)
        })
        container.addView(dropdown, LinearLayout.LayoutParams(LayoutHelper.dp(46), LinearLayout.LayoutParams.MATCH_PARENT))
        return container
    }

    private fun showRoleDropdown(anchor: View) {
        val ctx = anchor.context
        val menu = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(12).toFloat()
                setColor(0xFF20202D.toInt())
                setStroke(LayoutHelper.dp(1), 0xFF3A3A48.toInt())
            }
            setPadding(0, LayoutHelper.dp(6), 0, LayoutHelper.dp(6))
        }
        val popup = PopupWindow(menu, LayoutHelper.dp(220), LinearLayout.LayoutParams.WRAP_CONTENT, true).apply {
            elevation = LayoutHelper.dp(8).toFloat()
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            isOutsideTouchable = true
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
        }
        fun addRow(label: String, role: SfuRole) {
            val selected = role == selectedRole
            menu.addView(TextView(ctx).apply {
                text = label
                setTextColor(if (selected) 0xFF43B581.toInt() else 0xFFFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setPadding(LayoutHelper.dp(16), LayoutHelper.dp(12), LayoutHelper.dp(16), LayoutHelper.dp(12))
                isClickable = true
                isFocusable = true
                applyVoiceButtonPressFeedback()
                setOnClickListener {
                    selectedRole = role
                    updateRoleUi()
                    popup.dismiss()
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        addRow("Join as speaker", SfuRole.SPEAKER)
        addRow("Join as audience", SfuRole.AUDIENCE)
        menu.measure(
            View.MeasureSpec.makeMeasureSpec(LayoutHelper.dp(220), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        popup.showAsDropDown(anchor, 0, -(anchor.height + menu.measuredHeight + LayoutHelper.dp(4)))
        menu.alpha = 0f
        menu.translationY = LayoutHelper.dp(8).toFloat()
        menu.animate().alpha(1f).translationY(0f).setDuration(140).start()
    }

    private fun updateRoleUi() {
        joinButtonView?.text = if (selectedRole == SfuRole.SPEAKER) "Join as Speaker" else "Join as Audience"
    }

    private fun loadAvatar(holder: AvatarHolder, url: String?, avatarSizePx: Int) {
        if (url == holder.currentUrl && holder.avatarDrawable.hasPhoto()) return
        holder.currentUrl = url
        holder.avatarDrawable.setPhoto(null)
        holder.avatarDrawable.setLoadingPlaceholder(false)
        holder.cancellable?.cancel()
        holder.cancellable = null
        holder.avatarView.setImageDrawable(holder.avatarDrawable)

        if (url.isNullOrEmpty()) return
        val proxyUrl = avatarImgproxyUrl(url, avatarSizePx)
        val loader = MezonImageLoader.getInstance(context)
        val cached = loader.getBitmapFromMemory(proxyUrl, avatarSizePx, avatarSizePx)
        if (cached != null) {
            holder.avatarDrawable.setPhoto(cached)
            holder.avatarView.setImageDrawable(holder.avatarDrawable)
            return
        }
        holder.avatarDrawable.setLoadingPlaceholder(true)
        holder.cancellable = loader.load(
            proxyUrl, avatarSizePx, avatarSizePx,
            onSuccess = { bmp ->
                holder.cancellable = null
                holder.avatarDrawable.setLoadingPlaceholder(false)
                holder.avatarDrawable.setPhoto(bmp)
                holder.avatarView.setImageDrawable(holder.avatarDrawable)
            },
            onError = {
                holder.cancellable = null
                holder.avatarDrawable.setLoadingPlaceholder(false)
                holder.avatarView.setImageDrawable(holder.avatarDrawable)
            }
        )
    }

    private fun clearAvatarLoads() {
        for (holder in avatarHolders) {
            holder.cancellable?.cancel()
            holder.cancellable = null
        }
        avatarHolders.clear()
    }

    override fun dismiss() {
        clearAvatarLoads()
        super.dismiss()
    }
}
