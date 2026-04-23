package com.mezon.mobile.home.clans

import com.mezon.mobile.R
import com.mezon.mobile.network.CHANNEL_TYPE_CHANNEL

data class ClanTemplateChannel(
    val name: String,
    val type: Int,
    val isPrivate: Boolean = false
)

data class ClanTemplateCategory(
    val name: String,
    val channels: List<ClanTemplateChannel>
)

data class ClanTemplateSpec(
    val id: String,
    val titleResId: Int,
    val iconResId: Int,
    val categories: List<ClanTemplateCategory>
)

object CreateClanTemplates {
    val templates: List<ClanTemplateSpec> = listOf(
        ClanTemplateSpec(
            id = "gaming",
            titleResId = R.string.clan_template_gaming,
            iconResId = R.drawable.ic_gaming,
            categories = listOf(
                ClanTemplateCategory(
                    name = "",
                    channels = listOf(
                        ClanTemplateChannel("clips-highlights", CHANNEL_TYPE_CHANNEL),
                        ClanTemplateChannel("looking-for-group", CHANNEL_TYPE_CHANNEL)
                    )
                ),
                ClanTemplateCategory(
                    name = "Private Channels",
                    channels = listOf(
                        ClanTemplateChannel("admin-chat", CHANNEL_TYPE_CHANNEL, isPrivate = true)
                    )
                ),
                ClanTemplateCategory(
                    name = "Voice Channels",
                    channels = listOf(
                        ClanTemplateChannel("Lobby", CHANNEL_TYPE_VOICE),
                        ClanTemplateChannel("Gaming", CHANNEL_TYPE_VOICE)
                    )
                )
            )
        ),
        ClanTemplateSpec(
            id = "friends",
            titleResId = R.string.clan_template_friends,
            iconResId = R.drawable.ic_add_friend_image,
            categories = listOf(
                ClanTemplateCategory(
                    name = "",
                    channels = listOf(
                        ClanTemplateChannel("memes", CHANNEL_TYPE_CHANNEL),
                        ClanTemplateChannel("photos", CHANNEL_TYPE_CHANNEL)
                    )
                ),
                ClanTemplateCategory(
                    name = "Private Channels",
                    channels = listOf(
                        ClanTemplateChannel("private-chat", CHANNEL_TYPE_CHANNEL, isPrivate = true)
                    )
                ),
                ClanTemplateCategory(
                    name = "Voice Channels",
                    channels = listOf(
                        ClanTemplateChannel("Lounge", CHANNEL_TYPE_VOICE),
                        ClanTemplateChannel("Stream Room", CHANNEL_TYPE_VOICE)
                    )
                )
            )
        ),
        ClanTemplateSpec(
            id = "study-group",
            titleResId = R.string.clan_template_study_group,
            iconResId = R.drawable.ic_study,
            categories = listOf(
                ClanTemplateCategory(
                    name = "",
                    channels = listOf(
                        ClanTemplateChannel("homework-help", CHANNEL_TYPE_CHANNEL),
                        ClanTemplateChannel("session-planning", CHANNEL_TYPE_CHANNEL),
                        ClanTemplateChannel("off-topic", CHANNEL_TYPE_CHANNEL)
                    )
                ),
                ClanTemplateCategory(
                    name = "Private Channels",
                    channels = listOf(
                        ClanTemplateChannel("private-chat", CHANNEL_TYPE_CHANNEL, isPrivate = true)
                    )
                ),
                ClanTemplateCategory(
                    name = "Voice Channels",
                    channels = listOf(
                        ClanTemplateChannel("Lounge", CHANNEL_TYPE_VOICE),
                        ClanTemplateChannel("Study Room 1", CHANNEL_TYPE_VOICE),
                        ClanTemplateChannel("Study Room 2", CHANNEL_TYPE_VOICE)
                    )
                )
            )
        ),
        ClanTemplateSpec(
            id = "school-club",
            titleResId = R.string.clan_template_school_club,
            iconResId = R.drawable.ic_create_image,
            categories = listOf(
                ClanTemplateCategory(
                    name = "",
                    channels = listOf(
                        ClanTemplateChannel("meeting-plans", CHANNEL_TYPE_CHANNEL),
                        ClanTemplateChannel("off-topic", CHANNEL_TYPE_CHANNEL)
                    )
                ),
                ClanTemplateCategory(
                    name = "Private Channels",
                    channels = listOf(
                        ClanTemplateChannel("private-chat", CHANNEL_TYPE_CHANNEL, isPrivate = true)
                    )
                ),
                ClanTemplateCategory(
                    name = "Voice Channels",
                    channels = listOf(
                        ClanTemplateChannel("Lounge", CHANNEL_TYPE_VOICE),
                        ClanTemplateChannel("Meeting Room 1", CHANNEL_TYPE_VOICE),
                        ClanTemplateChannel("Meeting Room 2", CHANNEL_TYPE_VOICE)
                    )
                )
            )
        ),
        ClanTemplateSpec(
            id = "local-community",
            titleResId = R.string.clan_template_local_community,
            iconResId = R.drawable.ic_local_community,
            categories = listOf(
                ClanTemplateCategory(
                    name = "",
                    channels = listOf(
                        ClanTemplateChannel("events", CHANNEL_TYPE_CHANNEL),
                        ClanTemplateChannel("introductions", CHANNEL_TYPE_CHANNEL),
                        ClanTemplateChannel("resources", CHANNEL_TYPE_CHANNEL)
                    )
                ),
                ClanTemplateCategory(
                    name = "Private Channels",
                    channels = listOf(
                        ClanTemplateChannel("private-chat", CHANNEL_TYPE_CHANNEL, isPrivate = true)
                    )
                ),
                ClanTemplateCategory(
                    name = "Voice Channels",
                    channels = listOf(
                        ClanTemplateChannel("Lounge", CHANNEL_TYPE_VOICE),
                        ClanTemplateChannel("Meeting Room", CHANNEL_TYPE_VOICE)
                    )
                )
            )
        ),
        ClanTemplateSpec(
            id = "artists-creators",
            titleResId = R.string.clan_template_artists_creators,
            iconResId = R.drawable.ic_artist,
            categories = listOf(
                ClanTemplateCategory(
                    name = "",
                    channels = listOf(
                        ClanTemplateChannel("showcase", CHANNEL_TYPE_CHANNEL),
                        ClanTemplateChannel("ideas-and-feedback", CHANNEL_TYPE_CHANNEL)
                    )
                ),
                ClanTemplateCategory(
                    name = "Private Channels",
                    channels = listOf(
                        ClanTemplateChannel("private-chat", CHANNEL_TYPE_CHANNEL, isPrivate = true)
                    )
                ),
                ClanTemplateCategory(
                    name = "Voice Channels",
                    channels = listOf(
                        ClanTemplateChannel("Lounge", CHANNEL_TYPE_VOICE),
                        ClanTemplateChannel("Community Hangout", CHANNEL_TYPE_VOICE),
                        ClanTemplateChannel("Stream Room", CHANNEL_TYPE_VOICE)
                    )
                )
            )
        )
    )
}
