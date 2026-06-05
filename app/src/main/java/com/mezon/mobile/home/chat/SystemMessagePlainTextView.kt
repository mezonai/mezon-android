package com.mezon.mobile.home.chat

import android.content.Context
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.widget.TextView
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors

class SystemMessagePlainTextView(
    context: Context,
    themeColors: ThemeColors
) : TextView(context) {

    var onMentionClick: ((userId: String?, roleId: String?) -> Unit)? = null

    init {
        maxLines = 3
        ellipsize = android.text.TextUtils.TruncateAt.END
        includeFontPadding = false
        movementMethod = LinkMovementMethod.getInstance()
        linksClickable = true
        isFocusable = true
        isClickable = true
        highlightColor = 0x335865F2
        val paint = themeColors.systemMessageTextPaint
        setTextSize(TypedValue.COMPLEX_UNIT_PX, paint.textSize)
        setTextColor(paint.color)
        typeface = paint.typeface
        setLineSpacing(LayoutHelper.dpf(2f), 1f)
    }
}
