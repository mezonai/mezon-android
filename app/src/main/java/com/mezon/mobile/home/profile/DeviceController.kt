package com.mezon.mobile.home.profile

import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.di.IoDispatcher
import com.mezon.mobile.network.MezonSocket
import com.mezon.mezon.rtapi.Envelope
import com.mezon.mezon.rtapi.listDataSocket
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceController @Inject constructor(
    private val mezonSocket: MezonSocket,
    private val notificationCenter: NotificationCenter,
    @ApplicationScope private val appScope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    suspend fun fetchDevices(): Result<List<Device>> = withContext(ioDispatcher) {
        try {
            val env = mezonSocket.send {
                this.listDataSocket = listDataSocket {
                    apiName = "ListLogedDevice"
                }
            }
            if (env.messageCase != Envelope.MessageCase.LIST_DATA_SOCKET) {
                return@withContext Result.failure(Exception("Invalid response"))
            }
            val data = env.listDataSocket
            val listLoggedDevice = data.listLogedDevice
            val devices = listLoggedDevice.devicesList.map { deviceInfo ->
                Device(
                    deviceId = deviceInfo.deviceId.ifEmpty { "" },
                    deviceName = if (deviceInfo.deviceName.isNotEmpty()) deviceInfo.deviceName else null,
                    ip = if (deviceInfo.ip.isNotEmpty()) deviceInfo.ip else null,
                    lastActiveSeconds = deviceInfo.lastActiveSeconds.toLong(),
                    loginAtSeconds = deviceInfo.loginAtSeconds.toLong(),
                    platform = if (deviceInfo.platform.isNotEmpty()) deviceInfo.platform else null,
                    status = deviceInfo.status,
                    isCurrentDevice = deviceInfo.isCurrent,
                    location = if (deviceInfo.location.isNotEmpty()) deviceInfo.location else null
                )
            }
            Result.success(devices)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}