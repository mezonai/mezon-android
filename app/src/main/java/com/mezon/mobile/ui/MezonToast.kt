package com.mezon.mobile.ui

import com.mezon.mobile.MainActivity
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.ui.cells.ToastOverlay

object MezonToast {

    fun show(
        fragment: BaseFragment,
        type: ToastOverlay.ToastType,
        message: String,
        description: String? = null
    ) {
        val act = fragment.getParentActivity() as? MainActivity ?: return
        val parent = fragment.getLayoutContainer()
            ?: act.drawerLayoutContainer
        ToastOverlay(act, act.themeColors).show(
            parent,
            type,
            message,
            description
        )
    }
}
