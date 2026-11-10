package com.mezon.mobile.home.clans.settings

import com.mezon.mobile.R

object ClanSetting {

    sealed interface MenuRow {
        data class Navigate(
            val icon: Int,
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
                        R.drawable.mz_info_20,
                        R.string.clan_settings_overview,
                        R.string.menu_clan_overview_settings,
                    )
                )
            }
            if (perm.isCanEditRole) {
                add(
                    MenuRow.Navigate(
                        R.drawable.mz_paste_20,
                        R.string.clan_settings_audit_log,
                        R.string.menu_clan_audit_log,
                    )
                )
            }
            if (perm.hasAdminPermission || perm.hasManageClanPermission) {
                add(
                    MenuRow.Navigate(
                        R.drawable.mz_gamepad_20,
                        R.string.clan_settings_integrations,
                        R.string.menu_clan_integrations,
                    )
                )
            }
            add(MenuRow.Navigate(R.drawable.mz_emoji_20, R.string.clan_settings_emoji, R.string.menu_clan_emoji))
            add(MenuRow.Navigate(R.drawable.mz_sticker_20, R.string.clan_settings_sticker, R.string.menu_clan_sticker))
            add(MenuRow.Navigate(R.drawable.mz_speaker_low_24, R.string.clan_settings_sound, R.string.menu_clan_sound))
            if (perm.hasManageClanPermission) {
                add(
                    MenuRow.Navigate(
                        R.drawable.mz_community_20,
                        R.string.clan_settings_enable_community,
                        R.string.menu_clan_enable_community,
                    )
                )
            }
        }
    }

    fun userManagementSectionRows(perm: ClanSettingsPermissionState): List<MenuRow> {
        return buildList {
            add(MenuRow.Navigate(R.drawable.mz_members_20, R.string.clan_settings_members, R.string.menu_clan_members))
            if (perm.isCanEditRole) {
                add(MenuRow.Navigate(R.drawable.mz_role_20, R.string.clan_settings_roles, R.string.menu_clan_roles))
            }
            add(MenuRow.InvitePeople)
        }
    }
}
