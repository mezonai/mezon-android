package com.mezon.mobile.core

import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import com.mezon.mobile.ui.cells.ActionBarView
import javax.inject.Inject

abstract class BaseFragment : Fragment() {

    @Inject lateinit var themeColors: ThemeColors
    @Inject lateinit var notificationCenter: NotificationCenter

    private val observers = ArrayList<Pair<Int, NotificationCenter.Observer>>()

    protected var actionBar: ActionBarView? = null

    protected fun observe(eventId: Int, observer: NotificationCenter.Observer) {
        observers.add(eventId to observer)
        notificationCenter.addObserver(eventId, observer)
    }

    protected fun wrapWithActionBar(title: String, content: View): View {
        val bar = ActionBarView(requireContext(), themeColors).apply {
            setTitle(title)
            setBackClickListener { navigateBack() }
        }
        actionBar = bar

        observe(NotificationCenter.themeChanged) { _, _ -> bar.applyTheme() }

        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColors.background)
        }
        root.addView(bar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 56))
        root.addView(content, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))
        return root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        actionBar = null
        for ((eventId, observer) in observers) {
            notificationCenter.removeObserver(eventId, observer)
        }
        observers.clear()
    }

    protected fun navigateTo(fragment: Fragment, addToBackStack: Boolean = true) {
        val transaction = parentFragmentManager.beginTransaction()
            .setCustomAnimations(
                android.R.anim.slide_in_left,
                android.R.anim.slide_out_right,
                android.R.anim.slide_in_left,
                android.R.anim.slide_out_right
            )
            .replace(com.mezon.mobile.R.id.fragment_container, fragment)
        if (addToBackStack) transaction.addToBackStack(null)
        transaction.commit()
    }

    protected fun navigateBack() {
        parentFragmentManager.popBackStack()
    }
}
