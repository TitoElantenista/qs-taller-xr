package com.qs.tallerxr.ar

import android.content.Context

/** No Huawei SDK or service is loaded by the Google Play edition. */
@Suppress("UNUSED_PARAMETER")
internal fun huaweiServiceState(context: Context): ArServiceState = ArServiceState.DISABLED
