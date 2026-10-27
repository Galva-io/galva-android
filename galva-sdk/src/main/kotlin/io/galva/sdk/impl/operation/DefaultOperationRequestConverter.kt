package io.galva.sdk.impl.operation

import android.content.Context
import android.os.SystemClock
import io.galva.common.utils.AppInfoSource
import io.galva.common.utils.DateTimeFormatUtils
import io.galva.common.utils.DeviceUtils
import io.galva.common.utils.NetworkInfoSource
import io.galva.common.utils.OsInfoSource
import io.galva.common.utils.ScreenSource
import io.galva.network.request.messages.AliasMessage
import io.galva.network.request.messages.App
import io.galva.network.request.messages.BatchMessage
import io.galva.network.request.messages.Device
import io.galva.network.request.messages.IdentityMessage
import io.galva.network.request.messages.MessageContext
import io.galva.network.request.messages.Network
import io.galva.network.request.messages.OS
import io.galva.network.request.messages.Screen
import io.galva.core.protocol.operation.APIOperation
import io.galva.core.protocol.operation.AdvertisingProvider
import io.galva.core.protocol.operation.OperationRequestConverter
import io.galva.network.request.messages.CreateCommunicationEndpointMessage
import io.galva.network.request.messages.DeleteCommunicationEndpointMessage
import io.galva.network.request.messages.EndpointNotification
import io.galva.network.request.messages.EndpointNotification.*
import io.galva.network.request.messages.TrackMessage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

internal class DefaultOperationRequestConverter(
    private val context: Context, private val advertisingProvider: AdvertisingProvider
) : OperationRequestConverter {
    override fun operationToBatchMessage(operation: APIOperation): BatchMessage {
        val messageContext = createMessageContext(context)
        return when (operation) {
            is APIOperation.CreateAnonymousId -> {
                IdentityMessage(
                    timestamp = DateTimeFormatUtils.format(Calendar.getInstance()),
                    anonymousId = operation.anonymousId,
                    context = messageContext,
                    traits = JsonObject(
                        buildMap {
                            // The account id Play purchases carry for this identity (the anonymous
                            // id until the app passes an override to identify).
                            put(
                                $$"$gv_obfuscatedAccountId",
                                JsonPrimitive(operation.obfuscatedAccountId)
                            )
                            put(
                                $$"$gv_timezone",
                                JsonPrimitive(messageContext.timezone)
                            )
                            put(
                                $$"$gv_languageCode",
                                JsonPrimitive(DeviceUtils.getDetectedLanguage(context, Locale.getDefault().toLanguageTag()))
                            )
                            put(
                                $$"$gv_country",
                                JsonPrimitive(DeviceUtils.getDetectedCountry(context,Locale.getDefault().country))
                            )
                        })
                )
            }

            is APIOperation.Identify -> {

                IdentityMessage(
                    timestamp = DateTimeFormatUtils.format(
                        Calendar.getInstance()
                    ),
                    anonymousId = operation.anonymousId,
                    context = messageContext,
                    endUserId = operation.userId,
                    traits = JsonObject(
                        buildMap {
                            if (operation.email != null) {
                                put($$"$gv_email", JsonPrimitive(operation.email))
                            }
                            if (operation.obfuscatedAccountId != null) {
                                put(
                                    $$"$gv_obfuscatedAccountId",
                                    JsonPrimitive(operation.obfuscatedAccountId)
                                )
                            }
                            put(
                                $$"$gv_timezone",
                                JsonPrimitive(messageContext.timezone)
                            )
                            put(
                                $$"$gv_languageCode",
                                JsonPrimitive(messageContext.locale)
                            )
                            put(
                                $$"$gv_country",
                                JsonPrimitive(DeviceUtils.getDetectedCountry(context,Locale.getDefault().country))
                            )
                        })
                )
            }

            is APIOperation.UpdateUserProperties -> {
                IdentityMessage(
                    timestamp = DateTimeFormatUtils.format(Calendar.getInstance()),
                    anonymousId = operation.anonymousId,
                    context = messageContext,
                    traits = operation.properties
                )
            }

            is APIOperation.SetPushToken -> {
                CreateCommunicationEndpointMessage(
                    endpoint = PushNotification(
                        platform = operation.platform,
                        token = operation.token,
                    ),
                    timestamp = DateTimeFormatUtils.format(Calendar.getInstance()),
                    anonymousId = operation.anonymousId,
                    context = messageContext,
                )
            }

            is APIOperation.ClearPushToken -> {
                DeleteCommunicationEndpointMessage(
                    endpoint = PushNotification(
                        platform = operation.platform,
                        token = operation.token,
                    ),
                    timestamp = DateTimeFormatUtils.format(Calendar.getInstance()),
                    anonymousId = operation.anonymousId,
                    context = messageContext,
                )
            }
            is APIOperation.Track -> {
                TrackMessage(
                    event = operation.event,
                    timestamp = DateTimeFormatUtils.format(Calendar.getInstance()),
                    anonymousId = operation.anonymousId,
                    context = messageContext,
                    endUserId = operation.userId,
                    properties = operation.properties,
                )
            }
        }
    }

    // Fixed for the life of the process: read once rather than per operation (each read is one or
    // more binder calls: Settings, PackageManager, UiModeManager).
    private val deviceData by lazy { DeviceUtils.loadDeviceData(context) }
    private val os by lazy { OsInfoSource.read().run { OS(name, version) } }
    private val app by lazy { AppInfoSource.read(context)?.run { App(build, name, namespace, version) } }

    // Calls arrive in bursts (identify, properties, track); one network read serves a burst.
    @Volatile
    private var cachedNetwork: Pair<Long, Network>? = null

    private fun network(context: Context): Network {
        val now = SystemClock.elapsedRealtime()
        cachedNetwork?.let { (readAt, network) -> if (now - readAt < NETWORK_TTL_MS) return network }
        return NetworkInfoSource.read(context).run { Network(bluetooth, carrier, cellular, wifi) }
            .also { cachedNetwork = now to it }
    }

    private fun createMessageContext(context: Context): MessageContext {
        return MessageContext(
            device = deviceData.run {
                Device(
                    id = id,
                    adTrackingEnabled = advertisingProvider.adTrackingEnabled(),
                    advertisingId = advertisingProvider.advertisingId(),
                    manufacturer = manufacturer,
                    model = model,
                    name = name,
                    token = null,
                    deviceType = deviceType,
                    version = version
                )
            },
            locale = Locale.getDefault().toLanguageTag(),
            timezone = TimeZone.getDefault().id,
            network = network(context),
            os = os,
            // Changes with rotation; reading it is a field access.
            screen = ScreenSource.read(context).run {
                Screen(density, height, width)
            },
            app = app,
        )
    }

    private companion object {
        const val NETWORK_TTL_MS = 5_000L
    }
}
