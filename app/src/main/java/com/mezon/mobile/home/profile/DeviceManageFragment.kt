package com.mezon.mobile.home.profile

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.R
import com.mezon.mobile.core.AlertsCreator
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.di.FragmentEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DeviceManageFragment : BaseFragment() {

    companion object {
        private const val ICON_SIZE_DP = 48
        private const val CARD_PADDING_DP = 16
        private const val CARD_RADIUS_DP = 16
        private const val ICON_TEXT_GAP_DP = 16
        private const val BADGE_RADIUS_DP = 4
        private const val BLURPLE_COLOR = 0xFF5865F2.toInt()
    }

    private lateinit var deviceController: DeviceController
    private var devices: List<Device> = emptyList()
    private var loading = true

    private lateinit var scrollView: ScrollView
    private lateinit var contentContainer: LinearLayout
    private lateinit var loadingView: ProgressBar
    private lateinit var deviceListView: RecyclerView

    override fun onInject(entryPoint: FragmentEntryPoint) {
        deviceController = entryPoint.deviceController()
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        observe(NotificationCenter.themeChanged) { _, _, _ ->
            if (fragmentView != null) {
                fragmentView?.setBackgroundColor(themeColors.background)
                contentContainer?.setBackgroundColor(themeColors.background)
                deviceListView?.adapter?.notifyDataSetChanged()
            }
        }
        observe(NotificationCenter.languageChanged) { _, _, _ ->
            if (fragmentView != null) {
                deviceListView?.adapter?.notifyDataSetChanged()
            }
        }
        return true
    }

    override fun createView(context: Context): View {
        scrollView = ScrollView(context).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            setBackgroundColor(themeColors.background)
        }

        contentContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(LayoutHelper.dp(16), LayoutHelper.dp(16), LayoutHelper.dp(16), LayoutHelper.dp(16))
            setBackgroundColor(themeColors.background)
        }

        scrollView.addView(contentContainer)

        buildContent()

        return wrapWithActionBar(getString(R.string.setting_devices_title), scrollView)
    }

    private fun buildContent() {
        contentContainer.removeAllViews()

        val desc1 = createDescriptionText(getString(R.string.setting_devices_description1))
        contentContainer.addView(desc1)

        val desc2 = createDescriptionText(getString(R.string.setting_devices_description2))
        contentContainer.addView(desc2, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = LayoutHelper.dp(4) })

        loadingView = ProgressBar(requireContext()).apply {
            isIndeterminate = true
        }
        contentContainer.addView(loadingView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = LayoutHelper.dp(32)
            bottomMargin = LayoutHelper.dp(32)
        })

        deviceListView = RecyclerView(requireContext()).apply {
            layoutManager = LinearLayoutManager(context)
            isNestedScrollingEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
        }
        contentContainer.addView(deviceListView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = LayoutHelper.dp(16) })

        fetchDevicesFromSocket()
    }

    private fun createDescriptionText(text: String): TextView {
        return TextView(requireContext()).apply {
            setText(text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(themeColors.onSurfaceVariant)
        }
    }

    private fun fetchDevicesFromSocket() {
        fragmentScope.launch(Dispatchers.IO) {
            val result = deviceController.fetchDevices()
            withContext(Dispatchers.Main) {
                loading = false
                loadingView.visibility = View.GONE
                result.onSuccess { deviceList ->
                    devices = deviceList
                }.onFailure {
                    devices = emptyList()
                }
                buildDeviceList()
            }
        }
    }

    private fun buildDeviceList() {
        if (devices.isEmpty()) {
            deviceListView.visibility = View.GONE
            return
        }

        deviceListView.visibility = View.VISIBLE

        val currentDevice = devices.find { it.isCurrentDevice }
        val otherDevices = devices.filter { !it.isCurrentDevice }

        val items = mutableListOf<Device>()
        currentDevice?.let { items.add(it) }
        items.addAll(otherDevices)

        val adapter = DeviceListAdapter(items)
        deviceListView.adapter = adapter

        val spacingDp = 8
        val spacing = LayoutHelper.dp(spacingDp)
        deviceListView.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(
                outRect: android.graphics.Rect,
                view: View,
                parent: RecyclerView,
                state: RecyclerView.State
            ) {
                outRect.bottom = spacing
            }
        })
    }

    private fun getDeviceIcon(device: Device): Int {
        return when (device.platform?.lowercase()) {
            "mobile", "ios", "android" -> R.drawable.ic_mobile_device
            else -> R.drawable.ic_desktop_computer
        }
    }

    private inner class DeviceListAdapter(
        private val devices: List<Device>
    ) : RecyclerView.Adapter<DeviceListAdapter.DeviceViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
            val card = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val pad = LayoutHelper.dp(CARD_PADDING_DP)
                setPadding(pad, pad, pad, pad)
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                )
                val bg = GradientDrawable().apply {
                    cornerRadius = LayoutHelper.dp(CARD_RADIUS_DP).toFloat()
                    setColor(themeColors.getColor(ThemeColors.key_sheetItemBackground))
                }
                background = bg
            }
            return DeviceViewHolder(card)
        }

        override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
            holder.bind(devices[position])
        }

        override fun getItemCount(): Int = devices.size

        inner class DeviceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

            private var currentDevice: Device? = null

            init {
                itemView.setOnLongClickListener {
                    currentDevice?.let { device ->
                        if (!device.isCurrentDevice) {
                            showRemoveDialog(device)
                        }
                    }
                    true
                }
            }

            fun bind(device: Device) {
                currentDevice = device
                val container = itemView as LinearLayout
                container.removeAllViews()

                val iconView = ImageView(itemView.context).apply {
                    setImageResource(getDeviceIcon(device))
                    colorFilter = PorterDuffColorFilter(themeColors.onSurface, PorterDuff.Mode.SRC_IN)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                }
                container.addView(iconView, LinearLayout.LayoutParams(
                    LayoutHelper.dp(ICON_SIZE_DP),
                    LayoutHelper.dp(ICON_SIZE_DP)
                ))

                val infoContainer = LinearLayout(itemView.context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(LayoutHelper.dp(ICON_TEXT_GAP_DP), 0, 0, 0)
                }

                val nameRow = LinearLayout(itemView.context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }

                val deviceNameText = TextView(itemView.context).apply {
                    text = device.deviceName ?: getPlatformLabel(device.platform)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    setTextColor(themeColors.onSurface)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                }
                nameRow.addView(deviceNameText)

                if (device.isCurrentDevice) {
                    val badgeBg = GradientDrawable().apply {
                        cornerRadius = LayoutHelper.dp(BADGE_RADIUS_DP).toFloat()
                        setColor(0xFF5865F2.toInt())
                    }
                    val badge = TextView(itemView.context).apply {
                        text = getString(R.string.setting_devices_current_label)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                        setTextColor(0xFFFFFFFF.toInt())
                        val padH = LayoutHelper.dp(6)
                        val padV = LayoutHelper.dp(2)
                        setPadding(padH, padV, padH, padV)
                        background = badgeBg
                    }
                    nameRow.addView(badge, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { leftMargin = LayoutHelper.dp(8) })
                }

                infoContainer.addView(nameRow)

                val detailRow = LinearLayout(itemView.context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }

                val platformLabel = TextView(itemView.context).apply {
                    text = getPlatformLabel(device.platform)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTextColor(themeColors.onSurfaceVariant)
                }
                detailRow.addView(platformLabel)

                if (!device.location.isNullOrEmpty()) {
                    detailRow.addView(TextView(itemView.context).apply {
                        text = " • "
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                        setTextColor(themeColors.onSurfaceVariant)
                    })
                    val locationText = TextView(itemView.context).apply {
                        text = device.location
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                        setTextColor(themeColors.onSurfaceVariant)
                    }
                    detailRow.addView(locationText)
                }

                infoContainer.addView(detailRow, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = LayoutHelper.dp(4) })

                container.addView(infoContainer, LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                ))
            }

            private fun showRemoveDialog(device: Device) {
                AlertsCreator.createConfirmDialog(
                    requireContext(),
                    getString(R.string.setting_devices_remove_confirm_title),
                    getString(R.string.setting_devices_remove_confirm_message),
                    confirmText = getString(R.string.setting_devices_remove_confirm_yes),
                    cancelText = getString(R.string.setting_devices_remove_confirm_no),
                    destructive = true
                ) {
                    Toast.makeText(requireContext(), getString(R.string.setting_devices_remove_success), Toast.LENGTH_SHORT).show()
                    fetchDevicesFromSocket()
                }.show()
            }
        }
    }

    private fun getPlatformLabel(platform: String?): String = when (platform?.lowercase()) {
        "ios" -> getString(R.string.setting_devices_platform_ios)
        "android" -> getString(R.string.setting_devices_platform_android)
        "web" -> getString(R.string.setting_devices_platform_web)
        "desktop" -> getString(R.string.setting_devices_platform_desktop)
        "mobile" -> getString(R.string.setting_devices_platform_android)
        else -> getString(R.string.setting_devices_unknown)
    }
}