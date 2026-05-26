package com.mezon.mobile.home.chat

import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.mezon.mobile.R
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.AvatarDrawable
import com.mezon.mobile.core.BottomSheet
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.clans.CHANNEL_TYPE_VOICE
import com.mezon.mobile.home.clans.ChannelItemCell
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.ui.theme.ThemeMode
import com.mezon.mobile.util.ColorUtilities
import com.mezon.mobile.util.avatarImgproxyUrl


class UserProfileBottomSheet(
    context: android.content.Context,
    private val userId: Long,
    private val displayName: String,
    private val username: String,
    private val avatarUrl: String?,
    private val aboutMe: String? = null,
    private val memberSince: String? = null,
    private val isOwnProfile: Boolean = false,
    private val isDM: Boolean = false,
    private val roles: List<UserProfileRole> = emptyList(),
    private val listener: UserProfileListener? = null,
    private val voiceParticipantExtras: VoiceParticipantExtras? = null
) : BottomSheet(context) {

    interface UserProfileListener {
        fun onSendMessage(userId: Long) {}
        fun onVoiceCall(userId: Long) {}
        fun onAddFriend(userId: Long) {}
    }

    data class VoiceParticipantExtras(
        val showHeaderActions: Boolean,
        val onFriendClick: () -> Unit,
        val onTransferClick: () -> Unit,
        val canManageVoiceUser: Boolean,
        val showMuteAction: Boolean,
        val onMuteAction: () -> Unit,
        val onKickAction: () -> Unit,
        val showVoicePresence: Boolean,
        val voiceChannelLabel: String,
        val onJoinVoiceChannel: () -> Unit,
        val voiceChannelType: Int = CHANNEL_TYPE_VOICE,
        val voiceChannelPrivate: Boolean = false
    )

    data class UserProfileRole(
        val id: Long,
        val title: String,
        val color: Int,
        val iconUrl: String
    )

    companion object {
        private val rnRedStrong = 0xFFC61E1B.toInt()
        private val rnBgSuccess = 0xFF16A34A.toInt()
    }

    private val theme: ThemeColors = ThemeColors.instance

    private val rnProfileSectionTitleColor: Int
        get() = when (theme.resolvedMode) {
            ThemeMode.LIGHT -> 0xFF000000.toInt()
            else -> 0xFFFFFFFF.toInt()
        }

    init {
        if (voiceParticipantExtras != null) {
            containerHeight = (AndroidUtilities.displaySize.y * 0.6f).toInt()
        }
    }

    // RN color mappings
    private val primaryColor: Int
        get() = when (theme.resolvedMode) {
            ThemeMode.LIGHT -> 0xFFF4F4F8.toInt()
            ThemeMode.DARK -> 0xFF121218.toInt()
            ThemeMode.ABYSS -> 0xFF110B33.toInt()
            else -> 0xFF121218.toInt()
        }
    private val textStrongColor: Int
        get() = when (theme.resolvedMode) {
            ThemeMode.LIGHT -> 0xFF070709.toInt()
            ThemeMode.DARK -> 0xFFDFE0E4.toInt()
            ThemeMode.ABYSS -> 0xFFDFE0E4.toInt()
            else -> 0xFFDFE0E4.toInt()
        }
    private val textColor: Int
        get() = when (theme.resolvedMode) {
            ThemeMode.LIGHT -> 0xFF29292B.toInt()
            ThemeMode.DARK -> 0xFFCCCCCC.toInt()
            ThemeMode.ABYSS -> 0xFFCCCCCC.toInt()
            else -> 0xFFCCCCCC.toInt()
        }

    private var backdropColorView: View? = null
    private var profileAvatarView: ImageView? = null
    private val profileAvatarDrawable = AvatarDrawable()
    private var avatarLoadDisposable: MezonImageLoader.Cancellable? = null

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        val scrollView = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = false      // Allow avatar to overflow below backdrop
            clipToPadding = false
        }

        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false      // Allow avatar to overflow below backdrop
            clipToPadding = false
            setPadding(0, 0, 0, AndroidUtilities.navigationBarHeight)
        }

        rootLayout.addView(buildBackdropWithAvatar())

        val voiceEx = voiceParticipantExtras
        if (voiceEx != null && voiceEx.canManageVoiceUser) {
            rootLayout.addView(buildVoiceManageCard(voiceEx), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = LayoutHelper.dp(14)
                rightMargin = LayoutHelper.dp(14)
                topMargin = LayoutHelper.dp(30)
                bottomMargin = LayoutHelper.dp(12)
            })
        }

        rootLayout.addView(buildUserInfoCard(), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            leftMargin = LayoutHelper.dp(14)
            rightMargin = LayoutHelper.dp(14)
            topMargin = when {
                voiceEx == null -> LayoutHelper.dp(30)
                voiceEx.canManageVoiceUser -> 0
                else -> LayoutHelper.dp(30)
            }
            bottomMargin = LayoutHelper.dp(12)
        })

        val rolesCard = buildRolesCard()
        if (rolesCard != null) {
            rootLayout.addView(rolesCard, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = LayoutHelper.dp(14)
                rightMargin = LayoutHelper.dp(14)
                bottomMargin = LayoutHelper.dp(12)
            })
        }

        val detailsCard = buildDetailsCard()
        if (detailsCard != null) {
            rootLayout.addView(detailsCard, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = LayoutHelper.dp(14)
                rightMargin = LayoutHelper.dp(14)
                bottomMargin = LayoutHelper.dp(20)
            })
        }

        if (voiceEx != null && voiceEx.showVoicePresence && voiceEx.voiceChannelLabel.isNotBlank()) {
            rootLayout.addView(buildVoicePresenceCard(voiceEx), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = LayoutHelper.dp(14)
                rightMargin = LayoutHelper.dp(14)
                bottomMargin = LayoutHelper.dp(20)
            })
        }

        scrollView.addView(rootLayout, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        setCustomView(scrollView)
        super.onCreate(savedInstanceState)
        fixNavigationBar()

        contentLayout?.clipChildren = false
        contentLayout?.clipToPadding = false
        containerView?.clipChildren = false
        containerView?.clipToPadding = false

        loadProfileAvatarAndBackdropTint()
    }

    override fun dismiss() {
        avatarLoadDisposable?.cancel()
        avatarLoadDisposable = null
        super.dismiss()
    }

    private fun buildBackdropWithAvatar(): FrameLayout {
        val container = FrameLayout(context).apply {
            clipChildren = false
        }

        val backdrop = View(context).apply {
            setBackgroundColor(theme.profileSheetBackdropFallback)
        }
        backdropColorView = backdrop
        container.addView(backdrop, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, LayoutHelper.dp(120)
        ))

        voiceParticipantExtras?.takeIf { it.showHeaderActions }?.let { ex ->
            val headerIconTint = textColor
            val friendBtn = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setImageDrawable(MezonIcon.userPlusIcon.getDrawable(context).apply {
                    colorFilter = PorterDuffColorFilter(headerIconTint, PorterDuff.Mode.SRC_IN)
                })
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(theme.serverRailBg)
                }
                setPadding(LayoutHelper.dp(6), LayoutHelper.dp(6), LayoutHelper.dp(6), LayoutHelper.dp(6))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    dismiss()
                    ex.onFriendClick()
                }
            }
            val transferBtn = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setImageDrawable(MezonIcon.transactionIcon.getDrawable(context).apply {
                    colorFilter = PorterDuffColorFilter(headerIconTint, PorterDuff.Mode.SRC_IN)
                })
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(theme.serverRailBg)
                }
                setPadding(LayoutHelper.dp(6), LayoutHelper.dp(6), LayoutHelper.dp(6), LayoutHelper.dp(6))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    dismiss()
                    ex.onTransferClick()
                }
            }
            container.addView(
                transferBtn,
                FrameLayout.LayoutParams(LayoutHelper.dp(32), LayoutHelper.dp(32), Gravity.TOP or Gravity.END).apply {
                    topMargin = LayoutHelper.dp(10)
                    marginEnd = LayoutHelper.dp(50)
                }
            )
            container.addView(
                friendBtn,
                FrameLayout.LayoutParams(LayoutHelper.dp(32), LayoutHelper.dp(32), Gravity.TOP or Gravity.END).apply {
                    topMargin = LayoutHelper.dp(10)
                    marginEnd = LayoutHelper.dp(10)
                }
            )
        }

        val avatarSize = LayoutHelper.dp(80)
        profileAvatarDrawable.setInfo(userId, username)
        val avatarView = ImageView(context).apply {
            setImageDrawable(profileAvatarDrawable)
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(theme.profileSheetAvatarRingColor)
                setStroke(LayoutHelper.dp(4), theme.profileSheetAvatarRingColor)
            }
            setPadding(LayoutHelper.dp(3), LayoutHelper.dp(3), LayoutHelper.dp(3), LayoutHelper.dp(3))
        }
        profileAvatarView = avatarView
        container.addView(avatarView, FrameLayout.LayoutParams(
            avatarSize, avatarSize, Gravity.START or Gravity.BOTTOM
        ).apply {
            leftMargin = LayoutHelper.dp(14)
            bottomMargin = -LayoutHelper.dp(30)
        })

        container.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LayoutHelper.dp(120)
        )

        return container
    }

    private fun loadProfileAvatarAndBackdropTint() {
        val url = avatarUrl?.trim().orEmpty()
        if (url.isEmpty()) return
        val img = profileAvatarView ?: return
        val size = LayoutHelper.dp(80)
        val proxyUrl = avatarImgproxyUrl(url, size)
        val loader = MezonImageLoader.getInstance(context)
        loader.getBitmapFromMemory(proxyUrl, size, size)?.let { bmp ->
            profileAvatarDrawable.setPhoto(bmp)
            img.setImageDrawable(profileAvatarDrawable)
            backdropColorView?.setBackgroundColor(ColorUtilities.getDominantColor(bmp))
            return
        }
        avatarLoadDisposable = loader.load(
            proxyUrl,
            size,
            size,
            onSuccess = { bmp ->
                avatarLoadDisposable = null
                profileAvatarDrawable.setPhoto(bmp)
                profileAvatarView?.setImageDrawable(profileAvatarDrawable)
                backdropColorView?.setBackgroundColor(ColorUtilities.getDominantColor(bmp))
            },
            onError = {
                avatarLoadDisposable = null
            }
        )
    }

    // ─── User Info Card ───────────────────────────────────────────────

    private fun buildUserInfoCard(): LinearLayout {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(theme.channelPanelBg)
                cornerRadius = LayoutHelper.dp(8).toFloat()
            }
            setPadding(LayoutHelper.dp(16), LayoutHelper.dp(16),
                LayoutHelper.dp(16), LayoutHelper.dp(16))
        }

        // Display name — RN: textStrong, h6=14sp, fontWeight 600
        val nameView = TextView(context).apply {
            text = displayName
            setTextColor(textStrongColor)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)  // RN: h6 = verticalScale(14)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        card.addView(nameView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = LayoutHelper.dp(2) })

        if (username.isNotBlank() && !username.equals(displayName, ignoreCase = true)) {
            val usernameView = TextView(context).apply {
                text = "@$username"
                setTextColor(textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            }
            card.addView(usernameView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        if (!isOwnProfile && voiceParticipantExtras == null) {
            val actionsRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            actionsRow.addView(buildActionButton(
                context.getString(R.string.user_profile_send_message),
                R.drawable.ic_chat_icon,
                textColor
            ) {
                dismiss()
                val l = listener
                if (l != null) l.onSendMessage(userId)
                else Toast.makeText(context, R.string.feature_coming_soon, Toast.LENGTH_SHORT).show()
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = LayoutHelper.dp(14) })

            // Voice Call button
            actionsRow.addView(buildActionButton(
                context.getString(R.string.user_profile_voice_call),
                R.drawable.ic_phone_call_icon,
                textColor
            ) {
                dismiss()
                val l = listener
                if (l != null) l.onVoiceCall(userId)
                else Toast.makeText(context, R.string.feature_coming_soon, Toast.LENGTH_SHORT).show()
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = LayoutHelper.dp(14) })

            // Add Friend button
            actionsRow.addView(buildActionButton(
                context.getString(R.string.user_profile_add_friend),
                R.drawable.ic_user_plus_icon,
                0xFF42A869.toInt()  // baseColor.green
            ) {
                dismiss()
                val l = listener
                if (l != null) l.onAddFriend(userId)
                else Toast.makeText(context, R.string.feature_coming_soon, Toast.LENGTH_SHORT).show()
            })

            card.addView(actionsRow, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = LayoutHelper.dp(20) })
        }

        return card
    }


    private fun buildActionButton(
        label: String,
        iconRes: Int,
        labelColor: Int,
        onClick: () -> Unit
    ): LinearLayout {
        val button = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                setColor(primaryColor)
                cornerRadius = LayoutHelper.dp(8).toFloat()
            }
            setPadding(LayoutHelper.dp(10), LayoutHelper.dp(10),
                LayoutHelper.dp(10), LayoutHelper.dp(10))
            minimumWidth = LayoutHelper.dp(80)
            isClickable = true
            isFocusable = true
            // Ripple
            val outValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            foreground = context.getDrawable(outValue.resourceId)
            setOnClickListener { onClick() }
        }

        val icon = ImageView(context).apply {
            try { setImageResource(iconRes) } catch (_: Exception) {}
            setColorFilter(labelColor)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        button.addView(icon, LinearLayout.LayoutParams(
            LayoutHelper.dp(24), LayoutHelper.dp(24)
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = LayoutHelper.dp(6)
        })

        val text = TextView(context).apply {
            this.text = label
            setTextColor(labelColor)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)  
            gravity = Gravity.CENTER
            maxLines = 1
        }
        button.addView(text, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER_HORIZONTAL })

        return button
    }


    private fun buildRolesCard(): LinearLayout? {
        if (roles.isEmpty() || isDM) return null
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(theme.channelPanelBg)
                cornerRadius = LayoutHelper.dp(8).toFloat()
            }
            setPadding(LayoutHelper.dp(16), LayoutHelper.dp(16), LayoutHelper.dp(16), LayoutHelper.dp(12))
        }
        card.addView(TextView(context).apply {
            text = context.getString(R.string.menu_clan_roles)
            setTextColor(rnProfileSectionTitleColor)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = LayoutHelper.dp(10)
        })
        val rolesWrap = RoleWrapLayout(context).apply {
            horizontalSpacing = LayoutHelper.dp(10)
            verticalSpacing = LayoutHelper.dp(8)
        }
        roles.forEach { role ->
            rolesWrap.addView(buildRoleRow(role))
        }
        card.addView(
            rolesWrap,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        return card
    }

    private fun buildRoleRow(role: UserProfileRole): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(LayoutHelper.dp(8), LayoutHelper.dp(6), LayoutHelper.dp(8), LayoutHelper.dp(6))
            background = GradientDrawable().apply {
                setColor(primaryColor)
                cornerRadius = LayoutHelper.dp(8).toFloat()
            }
        }
        val color = if (role.color != 0) role.color else Color.parseColor("#99aab5")
        row.addView(View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
        }, LinearLayout.LayoutParams(LayoutHelper.dp(10), LayoutHelper.dp(10)).apply {
            marginEnd = LayoutHelper.dp(10)
        })
        row.addView(TextView(context).apply {
            text = role.title
            setTextColor(textStrongColor)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxWidth = LayoutHelper.dp(200)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return row
    }

    private class RoleWrapLayout(context: android.content.Context) : ViewGroup(context) {
        var horizontalSpacing: Int = 0
        var verticalSpacing: Int = 0

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val widthMode = MeasureSpec.getMode(widthMeasureSpec)
            val widthSize = MeasureSpec.getSize(widthMeasureSpec)
            val maxWidth = if (widthMode == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else widthSize - paddingLeft - paddingRight
            var x = 0
            var y = 0
            var lineHeight = 0
            var usedWidth = 0
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (child.visibility == GONE) continue
                measureChild(child, widthMeasureSpec, heightMeasureSpec)
                val childWidth = child.measuredWidth
                val childHeight = child.measuredHeight
                if (x > 0 && x + childWidth > maxWidth) {
                    x = 0
                    y += lineHeight + verticalSpacing
                    lineHeight = 0
                }
                if (x > 0) x += horizontalSpacing
                x += childWidth
                lineHeight = maxOf(lineHeight, childHeight)
                usedWidth = maxOf(usedWidth, x)
            }
            val measuredWidth = when (widthMode) {
                MeasureSpec.EXACTLY -> widthSize
                MeasureSpec.AT_MOST -> (paddingLeft + usedWidth + paddingRight).coerceAtMost(widthSize)
                else -> paddingLeft + usedWidth + paddingRight
            }
            val measuredHeight = y + lineHeight + paddingTop + paddingBottom
            setMeasuredDimension(
                measuredWidth,
                resolveSize(measuredHeight, heightMeasureSpec)
            )
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val maxWidth = width - paddingLeft - paddingRight
            var x = paddingLeft
            var y = paddingTop
            var lineHeight = 0
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (child.visibility == GONE) continue
                val childWidth = child.measuredWidth
                val childHeight = child.measuredHeight
                if (x > paddingLeft && (x - paddingLeft) + horizontalSpacing + childWidth > maxWidth) {
                    x = paddingLeft
                    y += lineHeight + verticalSpacing
                    lineHeight = 0
                }
                if (x > paddingLeft) x += horizontalSpacing
                child.layout(x, y, x + childWidth, y + childHeight)
                x += childWidth
                lineHeight = maxOf(lineHeight, childHeight)
            }
        }
    }

    private fun buildDetailsCard(): LinearLayout? {
        val hasMemberSince = !memberSince.isNullOrEmpty()
        val hasAboutMe = !aboutMe.isNullOrEmpty()
        if (!hasMemberSince && !hasAboutMe) return null

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(theme.channelPanelBg)
                cornerRadius = LayoutHelper.dp(8).toFloat()
            }
            setPadding(LayoutHelper.dp(20), LayoutHelper.dp(20),
                LayoutHelper.dp(20), LayoutHelper.dp(20))
        }

        if (hasMemberSince) {
            val titleView = TextView(context).apply {
                text = context.getString(R.string.user_profile_member_since)
                setTextColor(textStrongColor)
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f) 
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            card.addView(titleView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = LayoutHelper.dp(10) }) 

            val valueView = TextView(context).apply {
                text = memberSince
                setTextColor(textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            }
            card.addView(valueView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = if (hasAboutMe) LayoutHelper.dp(16) else 0 })
        }

        if (hasAboutMe) {
            val aboutTitle = TextView(context).apply {
                text = context.getString(R.string.user_profile_about_me)
                setTextColor(textStrongColor)
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)  
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            card.addView(aboutTitle, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = LayoutHelper.dp(10) }) 

            val aboutText = TextView(context).apply {
                text = aboutMe
                setTextColor(textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
                setTypeface(typeface, android.graphics.Typeface.ITALIC)
            }
            card.addView(aboutText)
        }

        return card
    }

    private fun buildVoiceManageCard(ex: VoiceParticipantExtras): LinearLayout {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(theme.channelPanelBg)
                cornerRadius = LayoutHelper.dp(8).toFloat()
            }
            setPadding(LayoutHelper.dp(16), LayoutHelper.dp(16), LayoutHelper.dp(16), LayoutHelper.dp(16))
        }
        card.addView(TextView(context).apply {
            text = context.getString(R.string.voice_room_settings_title)
            setTextColor(rnProfileSectionTitleColor)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = LayoutHelper.dp(10)
        })
        val actionsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
        }
        if (ex.showMuteAction) {
            actionsRow.addView(
                buildVoiceModerationActionItem(
                    icon = MezonIcon.microphoneSlashIcon,
                    title = context.getString(R.string.voice_room_mute_voice),
                    labelColor = rnRedStrong
                ) {
                    dismiss()
                    ex.onMuteAction()
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = LayoutHelper.dp(14)
                }
            )
        }
        actionsRow.addView(
            buildVoiceModerationActionItem(
                icon = MezonIcon.removeFriend,
                title = context.getString(R.string.voice_room_kick_voice),
                labelColor = rnRedStrong
            ) {
                dismiss()
                ex.onKickAction()
            }
        )
        card.addView(actionsRow)
        return card
    }

    private fun buildVoicePresenceCard(ex: VoiceParticipantExtras): LinearLayout {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(theme.channelPanelBg)
                cornerRadius = LayoutHelper.dp(8).toFloat()
            }
            setPadding(LayoutHelper.dp(16), LayoutHelper.dp(16), LayoutHelper.dp(16), LayoutHelper.dp(16))
        }
        card.addView(TextView(context).apply {
            text = context.getString(R.string.voice_profile_in_voice)
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = LayoutHelper.dp(10)
        })
        val voiceRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        voiceRow.addView(
            ImageView(context).apply {
                val d = ChannelItemCell.resolveChannelIcon(ex.voiceChannelType, ex.voiceChannelPrivate)
                    .getDrawable(context)
                    .mutate()
                d.colorFilter = PorterDuffColorFilter(textColor, PorterDuff.Mode.SRC_IN)
                setImageDrawable(d)
            },
            LinearLayout.LayoutParams(LayoutHelper.dp(20), LayoutHelper.dp(20)).apply {
                marginEnd = LayoutHelper.dp(12)
            }
        )
        voiceRow.addView(TextView(context).apply {
            text = ex.voiceChannelLabel
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            maxLines = 1
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(
            voiceRow,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = LayoutHelper.dp(16)
            }
        )
        card.addView(TextView(context).apply {
            text = context.getString(R.string.voice_profile_join_voice)
            gravity = Gravity.CENTER
            setPadding(0, LayoutHelper.dp(8), 0, LayoutHelper.dp(8))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(50).toFloat()
                setColor(rnBgSuccess)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                dismiss()
                ex.onJoinVoiceChannel()
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return card
    }

    private fun buildVoiceModerationActionItem(
        icon: MezonIcon,
        title: String,
        labelColor: Int,
        onClick: () -> Unit
    ): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(LayoutHelper.dp(10), LayoutHelper.dp(10), LayoutHelper.dp(10), LayoutHelper.dp(10))
            minimumWidth = LayoutHelper.dp(80)
            background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(8).toFloat()
                setColor(theme.serverRailBg)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        row.addView(ImageView(context).apply {
            setImageDrawable(icon.getDrawable(context).apply {
                colorFilter = PorterDuffColorFilter(labelColor, PorterDuff.Mode.SRC_IN)
            })
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }, LinearLayout.LayoutParams(LayoutHelper.dp(18), LayoutHelper.dp(18)).apply {
            marginEnd = LayoutHelper.dp(6)
        })
        row.addView(TextView(context).apply {
            this.text = title
            setTextColor(labelColor)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            maxLines = 1
        })
        return row
    }
}
