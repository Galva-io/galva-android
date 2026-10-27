package io.galva.sdk.impl.operation

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.galva.common.utils.JsonUtils
import io.galva.core.protocol.operation.APIOperation
import io.galva.core.protocol.operation.AdvertisingProvider
import io.galva.network.request.messages.BatchMessage
import io.galva.sdk.PushProvider
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DefaultOperationRequestConverterTest {
    private val converter = DefaultOperationRequestConverter(
        ApplicationProvider.getApplicationContext<Application>(),
        object : AdvertisingProvider {
            override fun adTrackingEnabled() = false
            override fun advertisingId(): String? = null
        },
    )

    private fun endpointOf(operation: APIOperation): JsonObject {
        val message: BatchMessage = converter.operationToBatchMessage(operation)
        return JsonUtils.defaultJson.encodeToJsonElement(BatchMessage.serializer(), message)
            .jsonObject.getValue("endpoint").jsonObject
    }

    @Test
    fun `provider wire values are stable`() {
        assertEquals(listOf("fcm", "hms", "adm"), PushProvider.entries.map { it.platform })
    }

    @Test
    fun `push endpoints carry the platform of the provider that issued the token`() {
        PushProvider.entries.forEach { provider ->
            val created = endpointOf(APIOperation.SetPushToken("anon-1", "token-1", provider.platform))
            val deleted = endpointOf(APIOperation.ClearPushToken("anon-1", "token-1", provider.platform))

            listOf(created, deleted).forEach { endpoint ->
                assertEquals("push-notification", endpoint.getValue("channelType").jsonPrimitive.content)
                assertEquals(provider.platform, endpoint.getValue("platform").jsonPrimitive.content)
                assertEquals("token-1", endpoint.getValue("token").jsonPrimitive.content)
            }
        }
    }
}
