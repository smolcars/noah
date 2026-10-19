package com.noahwallet.services.push

import com.noahwallet.api.generated.models.*
import com.noahwallet.services.server.ServerClient
import kotlinx.serialization.json.*

/** FCM's data.body is a JSON string; UnifiedPush sends the object directly. */
object PushPayload {
    fun decode(body: String): NotificationData = decode(ServerClient.json.parseToJsonElement(body))

    fun decode(body: JsonElement): NotificationData {
        val value = if (body is JsonPrimitive && body.isString) {
            ServerClient.json.parseToJsonElement(body.content)
        } else body
        val notification = ServerClient.json.decodeFromJsonElement<NotificationData>(value)
        when (notification) {
            is NotificationDataLightningInvoiceRequest -> require(notification.amount >= 0)
            is NotificationDataLightningClaimRequest -> require(notification.amountSat >= 0)
            is NotificationDataRecurringPaymentDue -> require(notification.dueCount in 0..4294967295L)
            else -> Unit
        }
        return notification
    }

    fun encode(notification: NotificationData): String = ServerClient.json.encodeToString(notification)
}
