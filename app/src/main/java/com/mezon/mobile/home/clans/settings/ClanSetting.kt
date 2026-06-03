package com.mezon.mobile.home.clans.settings

import com.mezon.mobile.R
import com.mezon.mobile.ui.cells.MezonIcon

object ClanSetting {

    sealed interface MenuRow {
        data class Navigate(
            val icon: MezonIcon,
            val labelRes: Int,
            val subScreenTitleRes: Int,
        ) : MenuRow

        data object InvitePeople : MenuRow
    }

    fun settingsSectionRows(perm: ClanSettingsPermissionState): List<MenuRow> {
        return buildList {
            if (perm.isShowOverviewOption) {
                add(
                    MenuRow.Navigate(
                        MezonIcon.circleInformation,
                        R.string.clan_settings_overview,
                        R.string.menu_clan_overview_settings,
                    )
                )
            }
            if (perm.isCanEditRole) {
                add(
                    MenuRow.Navigate(
                        MezonIcon.clipboardIcon,
                        R.string.clan_settings_audit_log,
                        R.string.menu_clan_audit_log,
                    )
                )
            }
            if (perm.hasAdminPermission || perm.hasManageClanPermission) {
                add(
                    MenuRow.Navigate(
                        MezonIcon.gameControllerIcon,
                        R.string.clan_settings_integrations,
                        R.string.menu_clan_integrations,
                    )
                )
            }
            add(MenuRow.Navigate(MezonIcon.faceIcon, R.string.clan_settings_emoji, R.string.menu_clan_emoji))
            add(MenuRow.Navigate(MezonIcon.sticker, R.string.clan_settings_sticker, R.string.menu_clan_sticker))
            add(MenuRow.Navigate(MezonIcon.voiceLowIcon, R.string.clan_settings_sound, R.string.menu_clan_sound))
            if (perm.hasManageClanPermission || perm.hasAdminPermission) {
                add(
                    MenuRow.Navigate(
                        MezonIcon.localOnboardingIcon,
                        R.string.clan_settings_onboarding,
                        R.string.menu_clan_onboarding,
                    )
                )
            }
            if (perm.hasManageClanPermission) {
                add(
                    MenuRow.Navigate(
                        MezonIcon.localCommunityIcon,
                        R.string.clan_settings_enable_community,
                        R.string.menu_clan_enable_community,
                    )
                )
            }
        }
    }

    fun userManagementSectionRows(perm: ClanSettingsPermissionState): List<MenuRow> {
        return buildList {
            add(MenuRow.Navigate(MezonIcon.groupIcon, R.string.clan_settings_members, R.string.menu_clan_members))
            if (perm.isCanEditRole) {
                add(MenuRow.Navigate(MezonIcon.shieldUserIcon, R.string.clan_settings_roles, R.string.menu_clan_roles))
            }
            add(MenuRow.InvitePeople)
        }
    }
}
