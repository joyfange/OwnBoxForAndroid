package io.nekohasekai.sagernet.aidl

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class SpeedDisplayData(
    // Bytes per second
    var txRateProxy: Long = 0L,
    var rxRateProxy: Long = 0L,
    var txRateDirect: Long = 0L,
    var rxRateDirect: Long = 0L,

    // Bytes for the current session
    // Outbound "bypass" usage is not counted
    var txTotal: Long = 0L,
    var rxTotal: Long = 0L,

    // Bytes for the current session through the direct ("bypass") outbound
    var txTotalDirect: Long = 0L,
    var rxTotalDirect: Long = 0L,

    // Profile id of the node a strategy group currently routes through (ActiveOutboundTracker, the same source the
    // notification title uses); for a plain node its own id, 0 when unknown.
    var activeLeafId: Long = 0L,
) : Parcelable
