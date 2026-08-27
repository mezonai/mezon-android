package com.mezon.mobile.core

import android.app.Dialog
import android.view.ViewGroup
import java.lang.ref.WeakReference

object InAppOverlayHost {

    private class Host(
        val dialog: WeakReference<Dialog>,
        val dismissOnOverlayTap: Boolean
    )

    private val hosts = ArrayList<Host>()

    fun register(dialog: Dialog, dismissOnOverlayTap: Boolean) {
        hosts.removeAll { it.dialog.get() == null || it.dialog.get() === dialog }
        hosts.add(Host(WeakReference(dialog), dismissOnOverlayTap))
    }

    fun unregister(dialog: Dialog) {
        hosts.removeAll { it.dialog.get() == null || it.dialog.get() === dialog }
    }

    fun topContainer(): ViewGroup? {
        for (index in hosts.indices.reversed()) {
            val dialog = hosts[index].dialog.get() ?: continue
            if (!dialog.isShowing) continue
            val decor = dialog.window?.decorView as? ViewGroup ?: continue
            if (!decor.isAttachedToWindow) continue
            return decor
        }
        return null
    }

    fun dismissTappableHosts() {
        val snapshot = ArrayList(hosts)
        hosts.removeAll { it.dismissOnOverlayTap }
        for (host in snapshot) {
            if (!host.dismissOnOverlayTap) continue
            host.dialog.get()?.takeIf { it.isShowing }?.dismiss()
        }
    }
}
