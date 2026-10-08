package com.qs.tallerxr.data

import android.content.Context
import android.os.storage.StorageManager

internal fun Context.allocatableStorageBytes(): Long = runCatching {
    getSystemService(StorageManager::class.java)
        .getAllocatableBytes(StorageManager.UUID_DEFAULT)
}.getOrElse { filesDir.usableSpace }
