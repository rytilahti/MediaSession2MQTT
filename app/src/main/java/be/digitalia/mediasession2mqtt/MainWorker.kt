package be.digitalia.mediasession2mqtt

import android.os.Build
import be.digitalia.mediasession2mqtt.mediasession.CurrentMediaControllerDetector
import be.digitalia.mediasession2mqtt.mediasession.metadataFlow
import be.digitalia.mediasession2mqtt.mediasession.playbackStateFlow
import be.digitalia.mediasession2mqtt.mqtt.MQTTPublishClient
import be.digitalia.mediasession2mqtt.mqtt.MQTTQoSLevel
import be.digitalia.mediasession2mqtt.mqtt.tryConnectAndPublish
import be.digitalia.mediasession2mqtt.mqttmediaplayer.MQTTPlaybackState
import be.digitalia.mediasession2mqtt.mqttmediaplayer.toMQTTPlaybackStateOrNull
import be.digitalia.mediasession2mqtt.mqttmediaplayer.toMediaTitle
import be.digitalia.mediasession2mqtt.settings.SettingsProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import javax.inject.Inject

class MainWorker @Inject constructor(
    currentMediaControllerDetector: CurrentMediaControllerDetector,
    private val settingsProvider: SettingsProvider,
    private val mqttClientFactory: MQTTPublishClient.Factory
) {
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val applicationIdFlow: Flow<String> =
        currentMediaControllerDetector.currentMediaController.mapLatest { mediaController ->
            mediaController?.packageName.orEmpty()
        }.distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val playbackStateFlow: Flow<MQTTPlaybackState> =
        currentMediaControllerDetector.currentMediaController.flatMapLatest { mediaController ->
            when (mediaController) {
                null -> flowOf(MQTTPlaybackState.idle)
                else -> mediaController.playbackStateFlow
                    .map { it.toMQTTPlaybackStateOrNull() }
                    .filterNotNull()
            }
        }.distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val mediaTitleFlow: Flow<String> =
        currentMediaControllerDetector.currentMediaController.flatMapLatest { mediaController ->
            when (mediaController) {
                null -> flowOf("")
                else -> mediaController.metadataFlow
                    .map { it.toMediaTitle() }
            }
        }.distinctUntilChanged()

    private suspend fun monitorSettings() {
        settingsProvider.connectionSettings.collectLatest { connectionSettings ->
            if (connectionSettings != null) {
                val client = mqttClientFactory.create(connectionSettings)
                try {
                    settingsProvider.messageSettings.collectLatest { (qosLevel, deviceId) ->
                        coroutineScope {
                            launch { publishApplicationId(client, qosLevel, deviceId) }
                            launch { publishPlaybackState(client, qosLevel, deviceId) }
                            launch { publishMediaTitle(client, qosLevel, deviceId) }
                        }
                    }
                } finally {
                    client.disconnectQuietly()
                }
            }
        }
    }

    private suspend fun publishHassConfiguration(
        client: MQTTPublishClient,
        qosLevel: MQTTQoSLevel,
        deviceId: Int
    ) {
        // Using as a unique identifier, but this may not be available on all devices.
        val serialNumber = Build.SERIAL

        @Serializable
        data class DeviceInfo(
            @EncodeDefault val name: String = "MediaSession2MQTT",
            @EncodeDefault val manufacturer: String = Build.MANUFACTURER,
            @EncodeDefault val model: String = Build.MODEL,
            // Passing serialNumber in as a variable causes build fail: "Exception during IR lowering"
            @EncodeDefault val identifiers: List<String> = listOf(Build.SERIAL),
            @EncodeDefault val serial_number: String = Build.SERIAL
        )

        @Serializable
        data class Sensor(
            val name: String,
            val state_topic: String,
            val unique_id: String,
            val device: DeviceInfo
        )

        // TODO: using a data class for storing sensor metadata would be nicer and let us configure
        //  icons etc. but this suffices for a proof-of-concept.
        val sensors = mapOf(
            "Application ID" to "$APPLICATION_ID_SUB_TOPIC",
            "Playback state" to "$PLAYBACK_STATE_SUB_TOPIC",
            "Media title" to "$MEDIA_TITLE_SUB_TOPIC"
        )

        val deviceInfo = DeviceInfo()

        sensors.forEach { (name, topic) ->
            val serializedName = name.lowercase().replace(" ", "_")
            val uniqueId = "mediaSession_${serialNumber}_${serializedName}"

            val sensor = Sensor(
                name,
                "$ROOT_TOPIC/$deviceId/$topic",
                uniqueId,
                deviceInfo
            )

            val discoveryConfig = Json.encodeToString(sensor)
            client.tryConnectAndPublish(
                qosLevel,
                "homeassistant/sensor/$deviceId/$serializedName/config",
                discoveryConfig
            )
        }
    }

    private suspend fun publishApplicationId(
        client: MQTTPublishClient,
        qosLevel: MQTTQoSLevel,
        deviceId: Int
    ) {
        applicationIdFlow.collect { applicationId ->
            // Configuration only changes if the application id changes, so this is probably fine?
            publishHassConfiguration(client, qosLevel, deviceId)
            client.tryConnectAndPublish(
                qosLevel,
                "$ROOT_TOPIC/$deviceId/$APPLICATION_ID_SUB_TOPIC",
                applicationId
            )
        }
    }

    private suspend fun publishPlaybackState(
        client: MQTTPublishClient,
        qosLevel: MQTTQoSLevel,
        deviceId: Int
    ) {
        playbackStateFlow.collect { playbackState ->
            client.tryConnectAndPublish(
                qosLevel,
                "$ROOT_TOPIC/$deviceId/$PLAYBACK_STATE_SUB_TOPIC",
                playbackState.name
            )
        }
    }

    private suspend fun publishMediaTitle(
        client: MQTTPublishClient,
        qosLevel: MQTTQoSLevel,
        deviceId: Int
    ) {
        mediaTitleFlow.collect { mediaTitle ->
            client.tryConnectAndPublish(
                qosLevel,
                "$ROOT_TOPIC/$deviceId/$MEDIA_TITLE_SUB_TOPIC",
                mediaTitle
            )
        }
    }

    fun start() {
        coroutineScope.launch {
            monitorSettings()
        }
    }

    companion object {
        private const val ROOT_TOPIC = "mediaSession"
        private const val APPLICATION_ID_SUB_TOPIC = "applicationId"
        private const val PLAYBACK_STATE_SUB_TOPIC = "playbackState"
        private const val MEDIA_TITLE_SUB_TOPIC = "mediaTitle"
    }
}