package com.mezon.mobile.network

import com.mezon.mobile.di.ApplicationScope
import com.mezon.mezon.api.ChannelMessage
import com.mezon.mezon.api.CreateEventRequest
import com.mezon.mezon.api.GiveCoffeeEvent
import com.mezon.mezon.api.MessageReaction
import com.mezon.mezon.api.Notification
import com.mezon.mezon.api.TokenSentEvent
import com.mezon.mezon.rtapi.AddClanUserEvent
import com.mezon.mezon.rtapi.AIAgentEnabledEvent
import com.mezon.mezon.rtapi.AllowAnonymousEvent
import com.mezon.mezon.rtapi.BannedUserEvent
import com.mezon.mezon.rtapi.CategoryEvent
import com.mezon.mezon.rtapi.ChannelAppEvent
import com.mezon.mezon.rtapi.ChannelCreatedEvent
import com.mezon.mezon.rtapi.ChannelDeletedEvent
import com.mezon.mezon.rtapi.ChannelPresenceEvent
import com.mezon.mezon.rtapi.ChannelUpdatedEvent
import com.mezon.mezon.rtapi.ClanDeletedEvent
import com.mezon.mezon.rtapi.ClanProfileUpdatedEvent
import com.mezon.mezon.rtapi.ClanUpdatedEvent
import com.mezon.mezon.rtapi.CustomStatusEvent
import com.mezon.mezon.rtapi.DeleteAccountEvent
import com.mezon.mezon.rtapi.DropdownBoxSelected
import com.mezon.mezon.rtapi.Envelope
import com.mezon.mezon.rtapi.EventEmoji
import com.mezon.mezon.rtapi.LastPinMessageEvent
import com.mezon.mezon.rtapi.LastSeenMessageEvent
import com.mezon.mezon.rtapi.MeetParticipantEvent
import com.mezon.mezon.rtapi.MessageButtonClicked
import com.mezon.mezon.rtapi.MessageTypingEvent
import com.mezon.mezon.rtapi.PermissionChangedEvent
import com.mezon.mezon.rtapi.PermissionSetEvent
import com.mezon.mezon.rtapi.RoleAssignedEvent
import com.mezon.mezon.rtapi.RoleEvent
import com.mezon.mezon.rtapi.SdTopicEvent
import com.mezon.mezon.rtapi.StickerCreateEvent
import com.mezon.mezon.rtapi.StickerDeleteEvent
import com.mezon.mezon.rtapi.StickerUpdateEvent
import com.mezon.mezon.rtapi.StreamingEndedEvent
import com.mezon.mezon.rtapi.StreamingJoinedEvent
import com.mezon.mezon.rtapi.StreamingLeavedEvent
import com.mezon.mezon.rtapi.StreamingStartedEvent
import com.mezon.mezon.rtapi.TransferOwnershipEvent
import com.mezon.mezon.rtapi.UnmuteEvent
import com.mezon.mezon.rtapi.UnpinMessageEvent
import com.mezon.mezon.rtapi.UserChannelAdded
import com.mezon.mezon.rtapi.UserChannelRemoved
import com.mezon.mezon.rtapi.UserClanRemoved
import com.mezon.mezon.rtapi.UserProfileUpdatedEvent
import com.mezon.mezon.rtapi.UserStatusEvent
import com.mezon.mezon.rtapi.VoiceEndedEvent
import com.mezon.mezon.rtapi.VoiceJoinedEvent
import com.mezon.mezon.rtapi.VoiceLeavedEvent
import com.mezon.mezon.rtapi.VoiceReactionSend
import com.mezon.mezon.rtapi.VoiceStartedEvent
import com.mezon.mezon.rtapi.WebrtcSignalingFwd
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SocketEventDispatcher @Inject constructor(
    private val mezonSocket: MezonSocket,
    @ApplicationScope private val scope: CoroutineScope
) {
    private val _channelMessages = MutableSharedFlow<ChannelMessage>(extraBufferCapacity = 32)
    val channelMessages: SharedFlow<ChannelMessage> = _channelMessages.asSharedFlow()

    private val _typingEvents = MutableSharedFlow<MessageTypingEvent>(extraBufferCapacity = 16)
    val typingEvents: SharedFlow<MessageTypingEvent> = _typingEvents.asSharedFlow()

    private val _messageReactions = MutableSharedFlow<MessageReaction>(extraBufferCapacity = 16)
    val messageReactions: SharedFlow<MessageReaction> = _messageReactions.asSharedFlow()

    private val _lastSeenMessageEvents = MutableSharedFlow<LastSeenMessageEvent>(extraBufferCapacity = 16)
    val lastSeenMessageEvents: SharedFlow<LastSeenMessageEvent> = _lastSeenMessageEvents.asSharedFlow()

    private val _lastPinMessageEvents = MutableSharedFlow<LastPinMessageEvent>(extraBufferCapacity = 8)
    val lastPinMessageEvents: SharedFlow<LastPinMessageEvent> = _lastPinMessageEvents.asSharedFlow()

    private val _unpinMessageEvents = MutableSharedFlow<UnpinMessageEvent>(extraBufferCapacity = 8)
    val unpinMessageEvents: SharedFlow<UnpinMessageEvent> = _unpinMessageEvents.asSharedFlow()

    private val _messageButtonClicked = MutableSharedFlow<MessageButtonClicked>(extraBufferCapacity = 8)
    val messageButtonClicked: SharedFlow<MessageButtonClicked> = _messageButtonClicked.asSharedFlow()

    private val _dropdownBoxSelected = MutableSharedFlow<DropdownBoxSelected>(extraBufferCapacity = 8)
    val dropdownBoxSelected: SharedFlow<DropdownBoxSelected> = _dropdownBoxSelected.asSharedFlow()

    private val _markAsRead = MutableSharedFlow<com.mezon.mezon.rtapi.MarkAsRead>(extraBufferCapacity = 16)
    val markAsRead: SharedFlow<com.mezon.mezon.rtapi.MarkAsRead> = _markAsRead.asSharedFlow()

    private val _channelPresenceEvents = MutableSharedFlow<ChannelPresenceEvent>(extraBufferCapacity = 16)
    val channelPresenceEvents: SharedFlow<ChannelPresenceEvent> = _channelPresenceEvents.asSharedFlow()

    private val _customStatusEvents = MutableSharedFlow<CustomStatusEvent>(extraBufferCapacity = 8)
    val customStatusEvents: SharedFlow<CustomStatusEvent> = _customStatusEvents.asSharedFlow()

    private val _userStatusEvents = MutableSharedFlow<UserStatusEvent>(extraBufferCapacity = 8)
    val userStatusEvents: SharedFlow<UserStatusEvent> = _userStatusEvents.asSharedFlow()

    private val _channelCreatedEvents = MutableSharedFlow<ChannelCreatedEvent>(extraBufferCapacity = 8)
    val channelCreatedEvents: SharedFlow<ChannelCreatedEvent> = _channelCreatedEvents.asSharedFlow()

    private val _channelDeletedEvents = MutableSharedFlow<ChannelDeletedEvent>(extraBufferCapacity = 8)
    val channelDeletedEvents: SharedFlow<ChannelDeletedEvent> = _channelDeletedEvents.asSharedFlow()

    private val _channelUpdatedEvents = MutableSharedFlow<ChannelUpdatedEvent>(extraBufferCapacity = 8)
    val channelUpdatedEvents: SharedFlow<ChannelUpdatedEvent> = _channelUpdatedEvents.asSharedFlow()

    private val _categoryEvents = MutableSharedFlow<CategoryEvent>(extraBufferCapacity = 8)
    val categoryEvents: SharedFlow<CategoryEvent> = _categoryEvents.asSharedFlow()

    private val _channelAppEvents = MutableSharedFlow<ChannelAppEvent>(extraBufferCapacity = 8)
    val channelAppEvents: SharedFlow<ChannelAppEvent> = _channelAppEvents.asSharedFlow()

    private val _clanUpdatedEvents = MutableSharedFlow<ClanUpdatedEvent>(extraBufferCapacity = 8)
    val clanUpdatedEvents: SharedFlow<ClanUpdatedEvent> = _clanUpdatedEvents.asSharedFlow()

    private val _clanDeletedEvents = MutableSharedFlow<ClanDeletedEvent>(extraBufferCapacity = 8)
    val clanDeletedEvents: SharedFlow<ClanDeletedEvent> = _clanDeletedEvents.asSharedFlow()

    private val _clanProfileUpdatedEvents = MutableSharedFlow<ClanProfileUpdatedEvent>(extraBufferCapacity = 8)
    val clanProfileUpdatedEvents: SharedFlow<ClanProfileUpdatedEvent> = _clanProfileUpdatedEvents.asSharedFlow()

    private val _transferOwnershipEvents = MutableSharedFlow<TransferOwnershipEvent>(extraBufferCapacity = 4)
    val transferOwnershipEvents: SharedFlow<TransferOwnershipEvent> = _transferOwnershipEvents.asSharedFlow()

    private val _allowAnonymousEvents = MutableSharedFlow<AllowAnonymousEvent>(extraBufferCapacity = 4)
    val allowAnonymousEvents: SharedFlow<AllowAnonymousEvent> = _allowAnonymousEvents.asSharedFlow()

    private val _userChannelAddedEvents = MutableSharedFlow<UserChannelAdded>(extraBufferCapacity = 8)
    val userChannelAddedEvents: SharedFlow<UserChannelAdded> = _userChannelAddedEvents.asSharedFlow()

    private val _userChannelRemovedEvents = MutableSharedFlow<UserChannelRemoved>(extraBufferCapacity = 8)
    val userChannelRemovedEvents: SharedFlow<UserChannelRemoved> = _userChannelRemovedEvents.asSharedFlow()

    private val _userClanRemovedEvents = MutableSharedFlow<UserClanRemoved>(extraBufferCapacity = 8)
    val userClanRemovedEvents: SharedFlow<UserClanRemoved> = _userClanRemovedEvents.asSharedFlow()

    private val _addClanUserEvents = MutableSharedFlow<AddClanUserEvent>(extraBufferCapacity = 8)
    val addClanUserEvents: SharedFlow<AddClanUserEvent> = _addClanUserEvents.asSharedFlow()

    private val _userProfileUpdatedEvents = MutableSharedFlow<UserProfileUpdatedEvent>(extraBufferCapacity = 8)
    val userProfileUpdatedEvents: SharedFlow<UserProfileUpdatedEvent> = _userProfileUpdatedEvents.asSharedFlow()

    private val _deleteAccountEvents = MutableSharedFlow<DeleteAccountEvent>(extraBufferCapacity = 4)
    val deleteAccountEvents: SharedFlow<DeleteAccountEvent> = _deleteAccountEvents.asSharedFlow()

    private val _banUserEvents = MutableSharedFlow<BannedUserEvent>(extraBufferCapacity = 4)
    val banUserEvents: SharedFlow<BannedUserEvent> = _banUserEvents.asSharedFlow()

    private val _voiceStartedEvents = MutableSharedFlow<VoiceStartedEvent>(extraBufferCapacity = 8)
    val voiceStartedEvents: SharedFlow<VoiceStartedEvent> = _voiceStartedEvents.asSharedFlow()

    private val _voiceEndedEvents = MutableSharedFlow<VoiceEndedEvent>(extraBufferCapacity = 8)
    val voiceEndedEvents: SharedFlow<VoiceEndedEvent> = _voiceEndedEvents.asSharedFlow()

    private val _voiceJoinedEvents = MutableSharedFlow<VoiceJoinedEvent>(extraBufferCapacity = 8)
    val voiceJoinedEvents: SharedFlow<VoiceJoinedEvent> = _voiceJoinedEvents.asSharedFlow()

    private val _voiceLeavedEvents = MutableSharedFlow<VoiceLeavedEvent>(extraBufferCapacity = 8)
    val voiceLeavedEvents: SharedFlow<VoiceLeavedEvent> = _voiceLeavedEvents.asSharedFlow()

    private val _voiceReactionEvents = MutableSharedFlow<VoiceReactionSend>(extraBufferCapacity = 8)
    val voiceReactionEvents: SharedFlow<VoiceReactionSend> = _voiceReactionEvents.asSharedFlow()

    private val _webrtcSignalingFwdEvents = MutableSharedFlow<WebrtcSignalingFwd>(extraBufferCapacity = 8)
    val webrtcSignalingFwdEvents: SharedFlow<WebrtcSignalingFwd> = _webrtcSignalingFwdEvents.asSharedFlow()

    private val _meetParticipantEvents = MutableSharedFlow<MeetParticipantEvent>(extraBufferCapacity = 8)
    val meetParticipantEvents: SharedFlow<MeetParticipantEvent> = _meetParticipantEvents.asSharedFlow()

    private val _streamingStartedEvents = MutableSharedFlow<StreamingStartedEvent>(extraBufferCapacity = 4)
    val streamingStartedEvents: SharedFlow<StreamingStartedEvent> = _streamingStartedEvents.asSharedFlow()

    private val _streamingEndedEvents = MutableSharedFlow<StreamingEndedEvent>(extraBufferCapacity = 4)
    val streamingEndedEvents: SharedFlow<StreamingEndedEvent> = _streamingEndedEvents.asSharedFlow()

    private val _streamingJoinedEvents = MutableSharedFlow<StreamingJoinedEvent>(extraBufferCapacity = 4)
    val streamingJoinedEvents: SharedFlow<StreamingJoinedEvent> = _streamingJoinedEvents.asSharedFlow()

    private val _streamingLeavedEvents = MutableSharedFlow<StreamingLeavedEvent>(extraBufferCapacity = 4)
    val streamingLeavedEvents: SharedFlow<StreamingLeavedEvent> = _streamingLeavedEvents.asSharedFlow()

    private val _roleEvents = MutableSharedFlow<RoleEvent>(extraBufferCapacity = 8)
    val roleEvents: SharedFlow<RoleEvent> = _roleEvents.asSharedFlow()

    private val _roleAssignEvents = MutableSharedFlow<RoleAssignedEvent>(extraBufferCapacity = 8)
    val roleAssignEvents: SharedFlow<RoleAssignedEvent> = _roleAssignEvents.asSharedFlow()

    private val _permissionSetEvents = MutableSharedFlow<PermissionSetEvent>(extraBufferCapacity = 8)
    val permissionSetEvents: SharedFlow<PermissionSetEvent> = _permissionSetEvents.asSharedFlow()

    private val _permissionChangedEvents = MutableSharedFlow<PermissionChangedEvent>(extraBufferCapacity = 8)
    val permissionChangedEvents: SharedFlow<PermissionChangedEvent> = _permissionChangedEvents.asSharedFlow()

    private val _stickerCreateEvents = MutableSharedFlow<StickerCreateEvent>(extraBufferCapacity = 4)
    val stickerCreateEvents: SharedFlow<StickerCreateEvent> = _stickerCreateEvents.asSharedFlow()

    private val _stickerUpdateEvents = MutableSharedFlow<StickerUpdateEvent>(extraBufferCapacity = 4)
    val stickerUpdateEvents: SharedFlow<StickerUpdateEvent> = _stickerUpdateEvents.asSharedFlow()

    private val _stickerDeleteEvents = MutableSharedFlow<StickerDeleteEvent>(extraBufferCapacity = 4)
    val stickerDeleteEvents: SharedFlow<StickerDeleteEvent> = _stickerDeleteEvents.asSharedFlow()

    private val _notifications = MutableSharedFlow<Notification>(extraBufferCapacity = 16)
    val notifications: SharedFlow<Notification> = _notifications.asSharedFlow()

    private val _tokenSentEvents = MutableSharedFlow<TokenSentEvent>(extraBufferCapacity = 4)
    val tokenSentEvents: SharedFlow<TokenSentEvent> = _tokenSentEvents.asSharedFlow()

    private val _unmuteEvents = MutableSharedFlow<UnmuteEvent>(extraBufferCapacity = 4)
    val unmuteEvents: SharedFlow<UnmuteEvent> = _unmuteEvents.asSharedFlow()

    private val _clanEventCreated = MutableSharedFlow<CreateEventRequest>(extraBufferCapacity = 4)
    val clanEventCreated: SharedFlow<CreateEventRequest> = _clanEventCreated.asSharedFlow()

    private val _giveCoffeeEvents = MutableSharedFlow<GiveCoffeeEvent>(extraBufferCapacity = 4)
    val giveCoffeeEvents: SharedFlow<GiveCoffeeEvent> = _giveCoffeeEvents.asSharedFlow()

    private val _sdTopicEvents = MutableSharedFlow<SdTopicEvent>(extraBufferCapacity = 8)
    val sdTopicEvents: SharedFlow<SdTopicEvent> = _sdTopicEvents.asSharedFlow()

    private val _aiagentEnabledEvents = MutableSharedFlow<AIAgentEnabledEvent>(extraBufferCapacity = 4)
    val aiagentEnabledEvents: SharedFlow<AIAgentEnabledEvent> = _aiagentEnabledEvents.asSharedFlow()

    private val _emojiEvents = MutableSharedFlow<EventEmoji>(extraBufferCapacity = 8)
    val emojiEvents: SharedFlow<EventEmoji> = _emojiEvents.asSharedFlow()

    init {
        scope.launch {
            mezonSocket.events.collect { envelope ->
                dispatch(envelope)
            }
        }
    }

    private suspend fun dispatch(envelope: Envelope) {
        when (envelope.messageCase) {
            Envelope.MessageCase.CHANNEL_MESSAGE ->
                _channelMessages.emit(envelope.channelMessage)
            Envelope.MessageCase.MESSAGE_TYPING_EVENT ->
                _typingEvents.emit(envelope.messageTypingEvent)
            Envelope.MessageCase.MESSAGE_REACTION_EVENT ->
                _messageReactions.emit(envelope.messageReactionEvent)
            Envelope.MessageCase.LAST_SEEN_MESSAGE_EVENT ->
                _lastSeenMessageEvents.emit(envelope.lastSeenMessageEvent)
            Envelope.MessageCase.LAST_PIN_MESSAGE_EVENT ->
                _lastPinMessageEvents.emit(envelope.lastPinMessageEvent)
            Envelope.MessageCase.UNPIN_MESSAGE_EVENT ->
                _unpinMessageEvents.emit(envelope.unpinMessageEvent)
            Envelope.MessageCase.MESSAGE_BUTTON_CLICKED ->
                _messageButtonClicked.emit(envelope.messageButtonClicked)
            Envelope.MessageCase.DROPDOWN_BOX_SELECTED ->
                _dropdownBoxSelected.emit(envelope.dropdownBoxSelected)
            Envelope.MessageCase.MARK_AS_READ ->
                _markAsRead.emit(envelope.markAsRead)
            Envelope.MessageCase.CHANNEL_PRESENCE_EVENT ->
                _channelPresenceEvents.emit(envelope.channelPresenceEvent)
            Envelope.MessageCase.CUSTOM_STATUS_EVENT ->
                _customStatusEvents.emit(envelope.customStatusEvent)
            Envelope.MessageCase.USER_STATUS_EVENT ->
                _userStatusEvents.emit(envelope.userStatusEvent)
            Envelope.MessageCase.CHANNEL_CREATED_EVENT ->
                _channelCreatedEvents.emit(envelope.channelCreatedEvent)
            Envelope.MessageCase.CHANNEL_DELETED_EVENT ->
                _channelDeletedEvents.emit(envelope.channelDeletedEvent)
            Envelope.MessageCase.CHANNEL_UPDATED_EVENT ->
                _channelUpdatedEvents.emit(envelope.channelUpdatedEvent)
            Envelope.MessageCase.CATEGORY_EVENT ->
                _categoryEvents.emit(envelope.categoryEvent)
            Envelope.MessageCase.CHANNEL_APP_EVENT ->
                _channelAppEvents.emit(envelope.channelAppEvent)
            Envelope.MessageCase.CLAN_UPDATED_EVENT ->
                _clanUpdatedEvents.emit(envelope.clanUpdatedEvent)
            Envelope.MessageCase.CLAN_DELETED_EVENT ->
                _clanDeletedEvents.emit(envelope.clanDeletedEvent)
            Envelope.MessageCase.CLAN_PROFILE_UPDATED_EVENT ->
                _clanProfileUpdatedEvents.emit(envelope.clanProfileUpdatedEvent)
            Envelope.MessageCase.TRANSFER_OWNERSHIP_EVENT ->
                _transferOwnershipEvents.emit(envelope.transferOwnershipEvent)
            Envelope.MessageCase.ALLOW_ANONYMOUS_EVENT ->
                _allowAnonymousEvents.emit(envelope.allowAnonymousEvent)
            Envelope.MessageCase.USER_CHANNEL_ADDED_EVENT ->
                _userChannelAddedEvents.emit(envelope.userChannelAddedEvent)
            Envelope.MessageCase.USER_CHANNEL_REMOVED_EVENT ->
                _userChannelRemovedEvents.emit(envelope.userChannelRemovedEvent)
            Envelope.MessageCase.USER_CLAN_REMOVED_EVENT ->
                _userClanRemovedEvents.emit(envelope.userClanRemovedEvent)
            Envelope.MessageCase.ADD_CLAN_USER_EVENT ->
                _addClanUserEvents.emit(envelope.addClanUserEvent)
            Envelope.MessageCase.USER_PROFILE_UPDATED_EVENT ->
                _userProfileUpdatedEvents.emit(envelope.userProfileUpdatedEvent)
            Envelope.MessageCase.DELETE_ACCOUNT_EVENT ->
                _deleteAccountEvents.emit(envelope.deleteAccountEvent)
            Envelope.MessageCase.BAN_USER_EVENT ->
                _banUserEvents.emit(envelope.banUserEvent)
            Envelope.MessageCase.VOICE_STARTED_EVENT ->
                _voiceStartedEvents.emit(envelope.voiceStartedEvent)
            Envelope.MessageCase.VOICE_ENDED_EVENT ->
                _voiceEndedEvents.emit(envelope.voiceEndedEvent)
            Envelope.MessageCase.VOICE_JOINED_EVENT ->
                _voiceJoinedEvents.emit(envelope.voiceJoinedEvent)
            Envelope.MessageCase.VOICE_LEAVED_EVENT ->
                _voiceLeavedEvents.emit(envelope.voiceLeavedEvent)
            Envelope.MessageCase.VOICE_REACTION_SEND ->
                _voiceReactionEvents.emit(envelope.voiceReactionSend)
            Envelope.MessageCase.WEBRTC_SIGNALING_FWD ->
                _webrtcSignalingFwdEvents.emit(envelope.webrtcSignalingFwd)
            Envelope.MessageCase.MEET_PARTICIPANT_EVENT ->
                _meetParticipantEvents.emit(envelope.meetParticipantEvent)
            Envelope.MessageCase.STREAMING_STARTED_EVENT ->
                _streamingStartedEvents.emit(envelope.streamingStartedEvent)
            Envelope.MessageCase.STREAMING_ENDED_EVENT ->
                _streamingEndedEvents.emit(envelope.streamingEndedEvent)
            Envelope.MessageCase.STREAMING_JOINED_EVENT ->
                _streamingJoinedEvents.emit(envelope.streamingJoinedEvent)
            Envelope.MessageCase.STREAMING_LEAVED_EVENT ->
                _streamingLeavedEvents.emit(envelope.streamingLeavedEvent)
            Envelope.MessageCase.ROLE_EVENT ->
                _roleEvents.emit(envelope.roleEvent)
            Envelope.MessageCase.ROLE_ASSIGN_EVENT ->
                _roleAssignEvents.emit(envelope.roleAssignEvent)
            Envelope.MessageCase.PERMISSION_SET_EVENT ->
                _permissionSetEvents.emit(envelope.permissionSetEvent)
            Envelope.MessageCase.PERMISSION_CHANGED_EVENT ->
                _permissionChangedEvents.emit(envelope.permissionChangedEvent)
            Envelope.MessageCase.STICKER_CREATE_EVENT ->
                _stickerCreateEvents.emit(envelope.stickerCreateEvent)
            Envelope.MessageCase.STICKER_UPDATE_EVENT ->
                _stickerUpdateEvents.emit(envelope.stickerUpdateEvent)
            Envelope.MessageCase.STICKER_DELETE_EVENT ->
                _stickerDeleteEvents.emit(envelope.stickerDeleteEvent)
            Envelope.MessageCase.NOTIFICATIONS -> {
                envelope.notifications.notificationsList.forEach { notification ->
                    _notifications.emit(notification)
                }
            }
            Envelope.MessageCase.TOKEN_SENT_EVENT ->
                _tokenSentEvents.emit(envelope.tokenSentEvent)
            Envelope.MessageCase.UNMUTE_EVENT ->
                _unmuteEvents.emit(envelope.unmuteEvent)
            Envelope.MessageCase.CLAN_EVENT_CREATED ->
                _clanEventCreated.emit(envelope.clanEventCreated)
            Envelope.MessageCase.GIVE_COFFEE_EVENT ->
                _giveCoffeeEvents.emit(envelope.giveCoffeeEvent)
            Envelope.MessageCase.SD_TOPIC_EVENT ->
                _sdTopicEvents.emit(envelope.sdTopicEvent)
            Envelope.MessageCase.AIAGENT_ENABLED_EVENT ->
                _aiagentEnabledEvents.emit(envelope.aiagentEnabledEvent)
            Envelope.MessageCase.EVENT_EMOJI ->
                _emojiEvents.emit(envelope.eventEmoji)
            else -> {}
            
        }
    }
}
