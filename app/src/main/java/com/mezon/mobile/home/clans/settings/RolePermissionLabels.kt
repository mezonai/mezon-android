package com.mezon.mobile.home.clans.settings

import android.content.Context
import com.mezon.mobile.R

object RolePermissionLabels {
    fun titleForSlug(context: Context, slug: String, fallbackTitle: String): String {
        val res = when (slug) {
            "administrator" -> R.string.clan_roles_perm_title_administrator
            "view-channel" -> R.string.clan_roles_perm_title_view_channel
            "manage-channel" -> R.string.clan_roles_perm_title_manage_channel
            "manage-clan" -> R.string.clan_roles_perm_title_manage_clan
            "manage-thread" -> R.string.clan_roles_perm_title_manage_thread
            "send-message" -> R.string.clan_roles_perm_title_send_message
            "delete-message" -> R.string.clan_roles_perm_title_delete_message
            "clan-owner" -> R.string.clan_roles_perm_title_clan_owner
            else -> 0
        }
        return if (res != 0) context.getString(res) else fallbackTitle
    }

    fun descForSlug(context: Context, slug: String, fallbackDescription: String): String {
        val res = when (slug) {
            "administrator" -> R.string.clan_roles_perm_desc_administrator
            "view-channel" -> R.string.clan_roles_perm_desc_view_channel
            "manage-channel" -> R.string.clan_roles_perm_desc_manage_channel
            "manage-clan" -> R.string.clan_roles_perm_desc_manage_clan
            "manage-thread" -> R.string.clan_roles_perm_desc_manage_thread
            "send-message" -> R.string.clan_roles_perm_desc_send_message
            "delete-message" -> R.string.clan_roles_perm_desc_delete_message
            else -> 0
        }
        return if (res != 0) context.getString(res) else fallbackDescription.ifBlank {
            context.getString(R.string.clan_roles_perm_desc_fallback)
        }
    }
}
