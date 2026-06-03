package io.galva.sdk.impl.operation

import android.content.Context
import io.galva.common.utils.AppInfoSource
import io.galva.common.utils.DateTimeFormatUtils
import io.galva.common.utils.DeviceUtils
import io.galva.common.utils.NetworkInfoSource
import io.galva.common.utils.OsInfoSource
import io.galva.common.utils.ScreenSource
import io.galva.common.utils.UUIDv7
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
import io.galva.core.protocol.operation.AdvertingProvider
import io.galva.core.protocol.operation.OperationRequestConverter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class DefaultOperationRequestConverter(
    private val context: Context, private val advertingProvider: AdvertingProvider
) : OperationRequestConverter {
    override fun operationToBatchMessage(operation: APIOperation): BatchMessage {
        return when (operation) {
            is APIOperation.CreateAnonymousId -> {
                IdentityMessage(
                    timestamp = DateTimeFormatUtils.format(Calendar.getInstance()),
                    anonymousId = operation.anonymousId,
                    context = createMessageContext(context),
                    traits = JsonObject(
                        buildMap {
                            put(
                                $$"$gv_obfuscatedAccountId",
                                JsonPrimitive(UUIDv7.randomUUID().toString())
                            )
                        }))
            }

            is APIOperation.Identify -> IdentityMessage(
                timestamp = DateTimeFormatUtils.format(
                    Calendar.getInstance()
                ),
                anonymousId = operation.anonymousId,
                context = createMessageContext(context),
                endUserId = operation.userId,
                traits = JsonObject(
                    buildMap {
                        if (operation.email != null) {
                            put("email", JsonPrimitive(operation.email))
                        }
                        if (operation.obfuscatedAccountId != null) {
                            put(
                                $$"$gv_obfuscatedAccountId",
                                JsonPrimitive(operation.obfuscatedAccountId)
                            )
                        }
                    }))


            is APIOperation.IdentifyEmail -> IdentityMessage(
                timestamp = DateTimeFormatUtils.format(Calendar.getInstance()),
                anonymousId = operation.anonymousId,
                context = createMessageContext(context)
            )

            is APIOperation.UpdateUserProperties -> {
                IdentityMessage(
                    timestamp = DateTimeFormatUtils.format(Calendar.getInstance()),
                    anonymousId = operation.anonymousId,
                    context = createMessageContext(context),
                    traits = operation.properties
                )
            }
        }
    }

    private fun createMessageContext(context: Context): MessageContext {
        return MessageContext(
            device = DeviceUtils.loadDeviceData(context).run {
                Device(
                    id = id,
                    adTrackingEnabled = advertingProvider.adTrackingEnabled(),
                    advertisingId = advertingProvider.advertingId(),
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
            network = NetworkInfoSource.read(context).run {
                Network(bluetooth, carrier, cellular, wifi)
            },
            os = OsInfoSource.read().run {
                OS(name, version)
            },
            screen = ScreenSource.read(context).run {
                Screen(density, height, width)
            },
            app = AppInfoSource.read(context)?.run {
                App(build, name, namespace, version)
            }

        )
    }

}