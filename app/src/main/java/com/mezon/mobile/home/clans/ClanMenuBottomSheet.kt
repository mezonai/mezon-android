package com.mezon.mobile.home.clans

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import com.mezon.mobile.R
import com.mezon.mobile.core.BottomSheet
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.cells.AvatarView
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.ui.cells.SwitchView
import com.mezon.mobile.home.clans.settings.ClanSettingsPermissionState
import com.mezon.mobile.home.clans.settings.ClanSettingsUiHelpers

class ClanMenuBottomSheet(
    context: Context,
    private val theme: ThemeColors,
    clanId: Long,
    clanName: String,
    clanLogo: String?,
    private val isCommunity: Boolean,
    private val totalMemberCount: Int,
    notificationMuted: Boolean,
    private val permissionState: ClanSettingsPermissionState,
    showEmptyCategories: Boolean,
    private val onOpenClanSettings: Runnable,
    private val onOpenAuditLog: Runnable,
    private val onOpenInvite: Runnable,
    private val onLeaveClan: Runnable,
    private val onDeleteClan: Runnable,
) : BottomSheet(context) {

    private fun showComingSoon() {
        Toast.makeText(context, context.getString(R.string.feature_coming_soon), Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fixNavigationBar(theme.surface)
    }

    init {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.surface)
            val padH = LayoutHelper.dp(20)
            setPadding(padH, 0, padH, LayoutHelper.dp(14))
        }

        root.addView(
            AvatarView(context).apply {
                setSizeDp(60)
                setRoundRadius(10f)
                setInfo(clanId, clanName)
                if (!clanLogo.isNullOrEmpty()) setImageUrl(clanLogo)
            },
            LayoutHelper.createLinear(60, 60).apply { bottomMargin = LayoutHelper.dp(12) }
        )

        root.addView(
            TextView(context).apply {
                text = clanName
                textSize = 18f
                setTextColor(theme.textStrong)
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                maxLines = 2
                setPadding(0, 0, 0, LayoutHelper.dp(8))
            },
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT)
        )

        val infoGap = LayoutHelper.dp(14)
        val inlineGap = LayoutHelper.dp(8)
        val infoRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (isCommunity) {
            val badgePadH = LayoutHelper.dp(8)
            val badgePadV = LayoutHelper.dp(4)
            infoRow.addView(
                TextView(context).apply {
                    text = context.getString(R.string.discover_community)
                    textSize = 11f
                    setTextColor(theme.onPrimary)
                    setPadding(badgePadH, badgePadV, badgePadH, badgePadV)
                    background = GradientDrawable().apply {
                        cornerRadius = LayoutHelper.dp(6f).toFloat()
                        setColor(theme.blurple)
                    }
                },
                LinearLayout.LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT)
            )
            infoRow.addView(
                Space(context),
                LinearLayout.LayoutParams(infoGap, LayoutHelper.dp(1))
            )
        }
        fun inlineRow(dotColor: Int, label: String): LinearLayout {
            return LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val dotSize = LayoutHelper.dp(10)
                addView(
                    View(context).apply {
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(dotColor)
                        }
                    },
                    LinearLayout.LayoutParams(dotSize, dotSize)
                )
                addView(
                    TextView(context).apply {
                        text = label
                        textSize = 12f
                        setTextColor(theme.colorText)
                    },
                    LinearLayout.LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT).apply {
                        leftMargin = inlineGap
                    }
                )
            }
        }
        val memberWord = if (totalMemberCount == 1) {
            context.getString(R.string.common_member)
        } else {
            context.getString(R.string.common_members)
        }
        val membersLabel = "$totalMemberCount $memberWord"
        infoRow.addView(
            inlineRow(theme.onSurfaceVariant, membersLabel),
            LinearLayout.LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT)
        )
        root.addView(
            infoRow,
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
                bottomMargin = LayoutHelper.dp(12)
            }
        )

        val inviteBtn = ClanSettingsUiHelpers.buildHorizontalActionButton(
            context, theme, MezonIcon.groupPlusIcon,
            context.getString(R.string.clan_menu_action_invite),
            Runnable { onOpenInvite.run() }
        )
        val notifyBtn = ClanSettingsUiHelpers.buildHorizontalActionButton(
            context,
            theme,
            if (notificationMuted) MezonIcon.bellSlashIcon else MezonIcon.bellIcon,
            context.getString(R.string.clan_menu_action_notifications),
            Runnable { showComingSoon() }
        )
        val settingsBtn = ClanSettingsUiHelpers.buildHorizontalActionButton(
            context,
            theme,
            MezonIcon.settingIcon,
            context.getString(R.string.clan_menu_action_settings),
            Runnable { onOpenClanSettings.run() }
        )

        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, LayoutHelper.dp(10), 0, LayoutHelper.dp(10))
            addView(inviteBtn, LinearLayout.LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))
            addView(Space(context), LinearLayout.LayoutParams(0, 0, 1f))
            addView(notifyBtn, LinearLayout.LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))
            addView(Space(context), LinearLayout.LayoutParams(0, 0, 1f))
            addView(settingsBtn, LinearLayout.LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))
        }
        root.addView(actionRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val spacer = 14f

        root.addView(
            ClanSettingsUiHelpers.buildMezonSection(
                context,
                theme,
                null,
                listOfNotNull(
                    ClanSettingsUiHelpers.buildMezonChevronRowWithoutIcon(context, theme, context.getString(R.string.clan_menu_mark_as_read), null, Runnable {
                        showComingSoon()
                    }),
                    ClanSettingsUiHelpers.buildMezonChevronRowWithoutIcon(
                        context,
                        theme,
                        context.getString(R.string.clan_menu_create_category),
                        null,
                        Runnable { showComingSoon() }
                    ).apply { visibility = if (permissionState.isCanEditRole) View.VISIBLE else View.GONE },
                    ClanSettingsUiHelpers.buildMezonChevronRowWithoutIcon(
                        context,
                        theme,
                        context.getString(R.string.clan_menu_create_event),
                        null,
                        Runnable { showComingSoon() }
                    )
                )
            ),
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, spacer, 0f, spacer)
        )

        root.addView(
            ClanSettingsUiHelpers.buildMezonSection(
                context,
                theme,
                null,
                listOfNotNull(
                    ClanSettingsUiHelpers.buildMezonChevronRowWithoutIcon(
                        context,
                        theme,
                        context.getString(R.string.clan_menu_edit_clan_profile),
                        null,
                        Runnable { showComingSoon() }
                    ),
                    ClanSettingsUiHelpers.buildMezonChevronRowWithoutIcon(
                        context,
                        theme,
                        context.getString(R.string.clan_menu_audit_log_option),
                        null,
                        Runnable { onOpenAuditLog.run() },
                    ).apply { visibility = if (permissionState.isCanEditRole) View.VISIBLE else View.GONE },
                    ClanSettingsUiHelpers.buildMezonChevronRowWithoutIcon(
                        context,
                        theme,
                        context.getString(R.string.clan_menu_leave_clan),
                        theme.error,
                        onLeaveClan,
                    ).apply { visibility = if (!permissionState.isClanOwner) View.VISIBLE else View.GONE },
                    ClanSettingsUiHelpers.buildMezonChevronRowWithoutIcon(
                        context,
                        theme,
                        context.getString(R.string.clan_menu_delete_clan),
                        theme.error,
                        onDeleteClan,
                    ).apply { visibility = if (permissionState.isClanOwner) View.VISIBLE else View.GONE }
                )
            ),
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 0f, 0f, spacer)
        )

        val swRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = LayoutHelper.dp(14)
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(theme.border)
        }
        swRow.addView(TextView(context).apply {
            text = context.getString(R.string.clan_menu_show_empty_categories)
            textSize = 15f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            setTextColor(theme.colorText)
        }, LinearLayout.LayoutParams(0, LayoutHelper.WRAP_CONTENT, 1f))

        swRow.addView(SwitchView(context, theme).apply {
            setChecked(showEmptyCategories, animated = false)
            onCheckedChange = {
                showComingSoon()
                setChecked(showEmptyCategories, animated = false)
            }
        })

        root.addView(ClanSettingsUiHelpers.buildMezonSection(context, theme, null, listOf(swRow)), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        setCustomView(root)
    }
}
