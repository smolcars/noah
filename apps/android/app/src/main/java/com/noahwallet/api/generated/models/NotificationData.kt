package com.noahwallet.api.generated.models

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
@JsonClassDiscriminator("notification_type")
@OptIn(ExperimentalSerializationApi::class)
public sealed interface NotificationData
