package com.mezon.mobile.ui.cells

import android.content.Context
import android.graphics.Canvas
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import com.mezon.mobile.R
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.theme.ThemeMode
import java.util.concurrent.ConcurrentHashMap

enum class MezonIcon(@DrawableRes val resId: Int) {
    channelText(R.drawable.ic_channel_open),
    channelTextLock(R.drawable.ic_channel_lock),
    channelTextWarning(R.drawable.ic_channel_open),
    channelVoice(R.drawable.ic_voice_icon),
    channelVoiceLock(R.drawable.ic_voice_icon),
    channelStream(R.drawable.ic_channel_stream),
    channelApp(R.drawable.ic_channel_app),
    calendarIcon(R.drawable.ic_calendar),
    chevronDownSmallIcon(R.drawable.ic_chevrondown),
    magnifyingIcon(R.drawable.ic_magnifying),
    searchIcon(R.drawable.ic_search_new_icon),
    userPlusIcon(R.drawable.ic_user_plus_icon),
    longCorner(R.drawable.ic_long_corner),
    shortCorner(R.drawable.ic_short_corner),
    bellIcon(R.drawable.ic_bell_icon),
    bellSlashIcon(R.drawable.ic_bell_slash_icon),
    threadIcon(R.drawable.ic_thread_bubble_icon),
    threadLockIcon(R.drawable.ic_threadlockicon),
    backArrowLarge(R.drawable.ic_arrow_left_svgrepo_com),
    inbox(R.drawable.ic_inbox),
    notificationTabMention(R.drawable.ic_notif_tab_mention),
    notificationTabMessages(R.drawable.ic_notif_tab_messages),
    notificationTabTopic(R.drawable.ic_notif_tab_topic),
    notificationTabForYou(R.drawable.ic_notif_tab_for_you),
    reply(R.drawable.ic_reply),
    replyDelete(R.drawable.ic_replydelete),
    faceIcon(R.drawable.ic_face_icon),
    trashIcon(R.drawable.ic_trash_icon),
    chatIcon(R.drawable.ic_chat_icon),
    pencilIcon(R.drawable.ic_pencil_iconlight),
    arrowAngleLeftUpIcon(R.drawable.ic_arrowangleleftuplight),
    arrowAngleRightUpIcon(R.drawable.ic_arrowanglerightuplight),
    copyIcon(R.drawable.ic_copy_icon),
    pinIcon(R.drawable.ic_pin_icon),
    markUnreadIcon(R.drawable.ic_chat_mark_unread_icon),
    downloadIcon(R.drawable.ic_download_icon),
    linkIcon(R.drawable.ic_link_icon),
    giftIcon(R.drawable.ic_gift_icon),
    discussionIcon(R.drawable.ic_discussion_icon),
    buzz(R.drawable.ic_buzz_icon),
    redFlag(R.drawable.ic_flag_icon),
    trashIconRed(R.drawable.ic_trash_icon),
    closeSmallBold(R.drawable.ic_close_small_bold_icon),
    circleInformation(R.drawable.ic_circle_information),
    scanQR(R.drawable.ic_qr_scan),
    treeHouse(R.drawable.ic_tree_house_icon),
    sticker(R.drawable.ic_sticker),
    shieldUserIcon(R.drawable.ic_shield_user_icon),
    shopSparkleIcon(R.drawable.ic_shop_sparkle_icon),
    groupPlusIcon(R.drawable.ic_group_plus_icon),
    groupIcon(R.drawable.ic_group_icon),
    gameControllerIcon(R.drawable.ic_game_controller_icon),
    clipboardIcon(R.drawable.ic_clipboard_icon),
    reactionIcon(R.drawable.ic_reaction_icon),
    circleXIcon(R.drawable.ic_circle_xicon),
    settingIcon(R.drawable.ic_settings_gear_icon),
    settingProfileIcon(R.drawable.ic_setting_profile_icon),
    circleIcon(R.drawable.ic_circle_icon),
    verifyIcon(R.drawable.ic_verify_icon),
    eyeIcon(R.drawable.ic_eye_icon),
    favoriteFilledIcon(R.drawable.ic_favorite_filled_icon),
    channelNotificaitionIcon(R.drawable.ic_channel_notification_icon),
    leaveGroupIcon(R.drawable.ic_leave_group_icon),
    idIcon(R.drawable.ic_idicon),
    plusLargeIcon(R.drawable.ic_plus_large_icon),
    webhookIcon(R.drawable.ic_webhook_icon),
    chevronSmallRightIcon(R.drawable.ic_chevron_right_small_icon),
    lockUnlockIcon(R.drawable.ic_lock_unlock_icon),
    lockIcon(R.drawable.ic_lock_icon),
    moreHorizontalIcon(R.drawable.ic_more_horizontal_icon),
    messagePlusIcon(R.drawable.ic_message_plus_icon),
    keyboardIcon(R.drawable.ic_keyboard_icon),
    attachmentIcon(R.drawable.ic_attachment_icon),
    atIcon(R.drawable.ic_at_icon),
    threadPlusIcon(R.drawable.ic_thread_plus_icon),
    sendMessageIcon(R.drawable.ic_send_message_icon),
    friendIcon(R.drawable.ic_friend_icon),
    userGroupIcon(R.drawable.ic_user_group_icon),
    microphoneIcon(R.drawable.ic_microphone_icon),
    microphoneDenyIcon(R.drawable.ic_microphone_deny_icon),
    microphoneSlashIcon(R.drawable.ic_microphone_slash_icon),
    boostTier2Icon(R.drawable.ic_boot_tier2_icon),
    nitroWheelIcon(R.drawable.ic_nitro_wheel_icon),
    myQRcodeIcon(R.drawable.ic_my_qrcode_icon),
    paintPaletteIcon(R.drawable.ic_paint_palette_icon),
    languageIcon(R.drawable.ic_language_icon),
    imageIcon(R.drawable.ic_image_icon),
    brandFacebookIcon(R.drawable.ic_branch_facebook_icon),
    brandTwitterIcon(R.drawable.ic_brand_twitter_icon),
    brandYoutubeIcon(R.drawable.ic_brand_youtube_icon),
    circlePlusPrimaryIcon(R.drawable.ic_circle_plus_primary_icon),
    circleQuestionIcon(R.drawable.ic_circle_question_icon),
    doorExitIcon(R.drawable.ic_door_exit_icon),
    voiceLowIcon(R.drawable.ic_voice_low_icon),
    eyeSlashIcon(R.drawable.ic_eye_slash_icon),
    userIcon(R.drawable.ic_user_icon),
    userCircleIcon(R.drawable.ic_user_circle_icon),
    userMinusIcon(R.drawable.ic_hashtag),
    forderIcon(R.drawable.ic_forder_icon),
    chevronSmallLeftIcon(R.drawable.ic_chevron_left_svgrepo_com),
    moreVerticalIcon(R.drawable.ic_more_vertical),
    arrowLargeLeftIcon(R.drawable.ic_arrowleftlarge),
    arrowLargeDownIcon(R.drawable.ic_arrow_down_svgrepo_com),
    closeIcon(R.drawable.ic_close_icon),
    closeLargeIcon(R.drawable.ic_close_large_icon),
    shareIcon(R.drawable.ic_share_box),
    locationIcon(R.drawable.ic_location_icon),
    checkmarkSmallIcon(R.drawable.ic_checkmark_small_icon),
    checkmarkLargeIcon(R.drawable.ic_checkmark_large_icon),
    clockIcon(R.drawable.ic_clock_icon),
    phoneCallIcon(R.drawable.ic_phone_call_icon),
    videoSlashIcon(R.drawable.ic_video_slash_icon),
    videoIcon(R.drawable.ic_video_icon),
    bravePermission(R.drawable.ic_brave_permission_icon),
    slashIcon(R.drawable.ic_slash_icon),
    uploadPlusIcon(R.drawable.ic_upload_plus_icon),
    mobileDeviceIcon(R.drawable.ic_mobile_device_icon),
    idleStatusIcon(R.drawable.ic_idle_status_icon),
    disturbStatusIcon(R.drawable.ic_disturb_status_icon),
    onlineStatusIcon(R.drawable.ic_online_status_icon),
    offlineStatusIcon(R.drawable.ic_offline_status_icon),
    reloadIcon(R.drawable.ic_reload_icon),
    transactionIcon(R.drawable.ic_transaction_icon),
    cameraFront(R.drawable.ic_camera_switch_front),
    anonymous(R.drawable.ic_anonymous),
    starIcon(R.drawable.ic_star_icon),
    anonymousAvatar(R.drawable.ic_anonymous_avatar),
    blockUser(R.drawable.ic_block_user),
    unblockUser(R.drawable.ic_unblock_user),
    wallet(R.drawable.ic_wallet),
    removeFriend(R.drawable.ic_user_minus_icon),
    playCircleIcon(R.drawable.ic_play_circle),
    payingIcon(R.drawable.ic_paying_icon),
    quickAction(R.drawable.ic_flash),
    addAction(R.drawable.ic_addition),
    deleteAction(R.drawable.ic_remove),
    editAction(R.drawable.ic_edit),
    closeDMIcon(R.drawable.ic_close_dmicon),
    bluetoothIcon(R.drawable.ic_bluetooth_icon),
    userFriendIcon(R.drawable.ic_user_friend_icon),
    userPendingIcon(R.drawable.ic_user_pending_icon),
    replyMsg(R.drawable.ic_reply_msg),
    auditLog(R.drawable.ic_audit_log),
    announcementIcon(R.drawable.ic_annoucement),
    forumIcon(R.drawable.ic_forum),
    peopleIcon(R.drawable.ic_people),
    historyIcon(R.drawable.ic_history),
    sendMoneyIcon(R.drawable.ic_send_money),
    ageRestrictedIcon(R.drawable.ic_age_restricted),
    recordIcon(R.drawable.ic_record),
    shareScreenIcon(R.drawable.ic_share_screen),
    shareScreenSlashIcon(R.drawable.ic_share_screen_slash),
    expandIcon(R.drawable.ic_expand),
    loadingIcon(R.drawable.ic_loading),
    minimizeIcon(R.drawable.ic_minimize),
    tickIcon(R.drawable.ic_tick),
    arrowLeftRightIcon(R.drawable.ic_arrow_left_right),
    cameraIcon(R.drawable.ic_camera),
    playIcon(R.drawable.ic_play),
    pauseIcon(R.drawable.ic_pause),
    fileIcon(R.drawable.ic_file),
    paperPlaneIcon(R.drawable.ic_paper_plane),
    heartIcon(R.drawable.ic_heart),
    objectIcon(R.drawable.ic_object),
    leafIcon(R.drawable.ic_leaf),
    bicycleIcon(R.drawable.ic_bicycle),
    bowlIcon(R.drawable.ic_bowl),
    emptyPinIcon(R.drawable.ic_empty_pin),
    circleExlaimionIcon(R.drawable.ic_circle_exclamation),
    homeIcon(R.drawable.ic_home),
    ownerIcon(R.drawable.ic_owner),
    emptySearchIcon(R.drawable.ic_empty_search),
    filterHorizontalIcon(R.drawable.ic_filter_horizontal),
    callCancelIcon(R.drawable.ic_call_cancel),
    callOutGoingIcon(R.drawable.ic_call_out_going),
    callInComingIcon(R.drawable.ic_call_in_coming),
    callMissIcon(R.drawable.ic_call_miss),
    logoMezon(R.drawable.ic_logo_mezon),
    activityIcon(R.drawable.ic_activity_icon),
    fireworksIcon(R.drawable.ic_fireworks_icon),
    chatImage(R.drawable.ic_chat_image),
    addFriendImage(R.drawable.ic_add_friend_image),
    createImage(R.drawable.ic_create_image),
    errorPage(R.drawable.ic_error_page),
    magicIcon(R.drawable.ic_magic_icon),
    transferOwnershipIcon(R.drawable.ic_transfer_ownership),
    noSignalIcon(R.drawable.ic_no_signal),
    unlinkIcon(R.drawable.ic_unlink_icon),
    vietnamFlagIcon(R.drawable.ic_vietnam_flag_icon),
    japanFlagIcon(R.drawable.ic_japan_flag_icon),
    usaFlagIcon(R.drawable.ic_usa_flag_icon),
    mailIcon(R.drawable.ic_mail),
    joinClanIcon(R.drawable.ic_join_clan_icon),
    communityIcon(R.drawable.ic_community_icon),
    hammerIcon(R.drawable.ic_hammer_icon),
    forwardAllIcon(R.drawable.ic_forward_all),
    hdIcon(R.drawable.ic_hd_icon),
    hdFullIcon(R.drawable.ic_hd_full_icon),
    advancedFunctionIcon(R.drawable.ic_advanced_function),
    pollIcon(R.drawable.ic_poll),
    raiseHandIcon(R.drawable.ic_raise_hand),
    deviceDestopIcon(R.drawable.ic_device_desktop),
    sparkleIcon(R.drawable.ic_sparkle),
    gamingIcon(R.drawable.ic_gaming),
    studyIcon(R.drawable.ic_study),
    localCommunityIcon(R.drawable.ic_local_community),
    artistIcon(R.drawable.ic_artist),
    businessIcon(R.drawable.ic_business_card),
    devicesIcon(R.drawable.ic_devices),
    searchFriendIcon(R.drawable.ic_search_friend),
    agentIcon(R.drawable.ic_agent),
    searchRnIcon(R.drawable.ic_search_rn),
    fileIconNew(R.drawable.ic_file_icon),
    shareContactIcon(R.drawable.ic_share_contact_icon),
    transferIcon(R.drawable.ic_transfer_icon),
    buzzAdvancedIcon(R.drawable.ic_buzz_advanced),
    sendMoneyAdvancedIcon(R.drawable.ic_transfer_advanced),
    locationIconGray(R.drawable.ic_location_icon_gray),
    fileIconGray(R.drawable.ic_file_icon_gray),
    shareContactIconGray(R.drawable.ic_share_contact_icon_gray),
    backspaceIcon(R.drawable.ic_backspace),
    shopIcon(R.drawable.ic_shop_icon),
    editProfileIcon(R.drawable.ic_edit_icon),
    balanceIcon(R.drawable.ic_balance_icon),
    historyTransactionIcon(R.drawable.ic_history_icon),
    threadPlusIconGray(R.drawable.ic_thread_plus_icon_gray),
    anonymousIconGray(R.drawable.ic_anonymous_icon_gray),
    ephemeralIconGray(R.drawable.ic_ephemeral_icon_gray),
    pollIconGray(R.drawable.ic_poll_icon_gray);

    fun getDrawable(context: Context): Drawable =
        loadDrawable(context, resId)

    fun getDrawable(context: Context, themeColors: ThemeColors): Drawable =
        when (this) {
            threadLockIcon -> {
                val resId = if (themeColors.resolvedMode == ThemeMode.LIGHT) {
                    R.drawable.ic_threadlockicon_light
                } else {
                    R.drawable.ic_threadlockicon
                }
                loadDrawable(context, resId)
            }
            scanQR -> {
                val resId = if (themeColors.resolvedMode == ThemeMode.LIGHT) {
                    R.drawable.ic_qr_scan_light
                } else {
                    R.drawable.ic_qr_scan
                }
                loadDrawable(context, resId)
            }
            else -> getDrawable(context)
        }

    fun getDrawable(context: Context, tintColor: Int): Drawable =
        getDrawable(context).apply {
            colorFilter = PorterDuffColorFilter(tintColor, PorterDuff.Mode.SRC_IN)
        }

    companion object {

        private val constantStateCache = ConcurrentHashMap<Int, Drawable.ConstantState>()

        fun loadDrawable(context: Context, @DrawableRes resId: Int): Drawable {
            val cached = constantStateCache[resId]
            if (cached != null) return cached.newDrawable(context.resources).mutate()
            val d = ContextCompat.getDrawable(context, resId)!!
            d.constantState?.let { constantStateCache[resId] = it }
            return d.mutate()
        }

        fun loadDrawable(context: Context, @DrawableRes resId: Int, tintColor: Int): Drawable =
            loadDrawable(context, resId).apply {
                colorFilter = PorterDuffColorFilter(tintColor, PorterDuff.Mode.SRC_IN)
            }

        fun drawIcon(canvas: Canvas, drawable: Drawable, cx: Int, cy: Int, size: Int) {
            val half = size / 2
            drawable.setBounds(cx - half, cy - half, cx + half, cy + half)
            drawable.draw(canvas)
        }

        fun drawIcon(canvas: Canvas, drawable: Drawable, left: Int, top: Int, right: Int, bottom: Int) {
            drawable.setBounds(left, top, right, bottom)
            drawable.draw(canvas)
        }
    }
}
