package com.mezon.mobile.home.voice

import com.mezon.mobile.ui.cells.setIconColor
import android.content.Context
import android.graphics.drawable.Drawable
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.mezon.mobile.R
import com.mezon.mobile.core.ThemeColors

enum class VoiceActivityBadge(@StringRes val labelRes: Int, @DrawableRes private val icon: Int) {
    IN_VOICE(R.string.voice_profile_in_voice, R.drawable.mz_speaker_24),
    SHARING_SCREEN(R.string.voice_profile_sharing_screen, R.drawable.mz_screen_share_24);

    // Voice badges follow the bounds set by the surrounding text or avatar.
    internal fun loadIcon(context: Context, theme: ThemeColors): Drawable =
        requireNotNull(ContextCompat.getDrawable(context, icon)).mutate().also {
            it.setIconColor(theme.voiceActiveGreen)
        }
}
