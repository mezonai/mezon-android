package com.mezon.mobile.home.chat

import android.content.Context
import android.os.Bundle
import com.mezon.mobile.R
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.BottomSheet
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.cells.MezonIcon

/**
 * Web `FileSelectionModal`: tap (+) → choose Upload a file or Create poll.
 */
class FileSelectionBottomSheet(
    context: Context,
    private val theme: ThemeColors,
    private val showCreatePoll: Boolean,
    private val onUploadFile: () -> Unit,
    private val onCreatePoll: () -> Unit
) : BottomSheet(context) {

    init {
        val labels = ArrayList<CharSequence>(2)
        val icons = ArrayList<Int>(2)
        labels.add(context.getString(R.string.file_selection_upload))
        icons.add(MezonIcon.fileIconGray.resId)
        if (showCreatePoll) {
            labels.add(context.getString(R.string.file_selection_create_poll))
            icons.add(MezonIcon.pollIconGray.resId)
        }
        setDimBehind(true)
        setItems(labels.toTypedArray(), icons.toIntArray(), null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fixNavigationBar(theme.surface)
        val textColor = theme.onSurface
        val iconColor = theme.onSurface
        getItemViews().forEachIndexed { index, cell ->
            cell.setTextColor(textColor)
            cell.setIconColor(iconColor)
            cell.setOnClickListener {
                when (index) {
                    0 -> dismissThen(onUploadFile)
                    1 -> if (showCreatePoll) dismissThen(onCreatePoll)
                }
            }
        }
    }

    /** Run action after sheet is fully gone so [presentFragment] is not hidden behind this dialog. */
    private fun dismissThen(action: () -> Unit) {
        setOnHideListener {
            setOnHideListener(null)
            AndroidUtilities.runOnUIThread(action)
        }
        dismiss()
    }

    companion object {
        fun show(
            context: Context,
            theme: ThemeColors,
            showCreatePoll: Boolean,
            onUploadFile: () -> Unit,
            onCreatePoll: () -> Unit
        ) {
            FileSelectionBottomSheet(context, theme, showCreatePoll, onUploadFile, onCreatePoll).show()
        }
    }
}
