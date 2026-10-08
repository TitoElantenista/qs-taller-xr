package com.qs.tallerxr.data

import kotlinx.serialization.json.Json

/** Lenient JSON shared by the package catalog and the workshop state. */
object JsonCodec {
    val value = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
}
