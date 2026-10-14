package com.mezon.mobile.home.voice

import androidx.annotation.StringRes
import com.mezon.mobile.R
import com.mezon.mobile.ui.cells.MezonIcon

enum class VoiceActivityBadge(@StringRes val labelRes: Int, val icon: MezonIcon) {
    IN_VOICE(R.string.voice_profile_in_voice, MezonIcon.channelVoice),
    SHARING_SCREEN(R.string.voice_profile_sharing_screen, MezonIcon.voiceScreenShareIcon),
}
