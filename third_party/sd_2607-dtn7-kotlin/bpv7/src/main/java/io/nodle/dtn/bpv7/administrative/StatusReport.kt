package io.nodle.dtn.bpv7.administrative

import io.nodle.dtn.bpv7.Bundle
import io.nodle.dtn.bpv7.DtnTime
import io.nodle.dtn.bpv7.dtnTimeNow
import io.nodle.dtn.bpv7.eid.nullDtnEid
import io.nodle.dtn.bpv7.isFragment
import io.nodle.dtn.bpv7.isFlagSet
import io.nodle.dtn.bpv7.BundleV7Flags
import io.nodle.dtn.bpv7.getPayloadSize
import java.net.URI
import java.util.*

/**
 * @author Lucien Loiseau on 17/02/21.
 */

data class StatusReport(
    var received: DtnTime = 0,
    var forwarded: DtnTime = 0,
    var delivered: DtnTime = 0,
    var deleted: DtnTime = 0,
    var otherAssertions: MutableList<StatusItem> = mutableListOf(),
    var bundleStatusReportReason: Int = StatusReportReason.NoInformation.code,
    var sourceNodeId: URI = nullDtnEid(),
    var creationTimestamp: DtnTime = 0,
    var sequenceNumber: Long = 0,
    var isFragment: Boolean = false,
    var fragmentOffset: Long = 0,
    var appDataLength: Long = 0,
    var reportStatusTime: Boolean = true,
    var assertedStatuses: Set<StatusAssertion> = emptySet()
) : AdministrativeData()

fun statusReport(bundle: Bundle): StatusReport =
    StatusReport(
        sourceNodeId = bundle.primaryBlock.source,
        creationTimestamp = bundle.primaryBlock.creationTimestamp,
        sequenceNumber = bundle.primaryBlock.sequenceNumber,
        isFragment = bundle.primaryBlock.isFragment(),
        fragmentOffset = bundle.primaryBlock.fragmentOffset,
        appDataLength = if (bundle.primaryBlock.isFragment()) bundle.getPayloadSize() else 0,
        reportStatusTime = bundle.isFlagSet(BundleV7Flags.RequestStatusTime)
    )

data class StatusItem(
    var statusAssertion: Int,
    var asserted: Boolean = false,
    var timestamp: DtnTime = 0,
    var hasTimestamp: Boolean = true,
)

enum class StatusAssertion(val code: Int) {
    ReceivedBundle(0),
    ForwardedBundle(1),
    DeliveredBundle(2),
    DeletedBundle(3);

    override fun toString(): String {
        return this.name + "($code)"
    }
}

enum class StatusReportReason(val code: Int) {
    // NoInformation is the "No additional information" bundle status report
    // reason code.
    NoInformation(0),

    // LifetimeExpired is the "Lifetime expired" bundle status report reason code.
    LifetimeExpired(1),

    // ForwardUnidirectionalLink is the "Forwarded over unidirectional link"
    // bundle status report reason code.
    ForwardUnidirectionalLink(2),

    // TransmissionCanceled is the "Transmission canceled" bundle status report
    // reason code.
    TransmissionCanceled(3),

    // DepletedStorage is the "Depleted storage" bundle status report reason code.
    DepletedStorage(4),

    // DestEndpointUnintelligible is the "Destination endpoint ID unintelligible"
    // bundle status report reason code.
    DestEndpointUnintelligible(5),

    // NoRouteToDestination is the "No known route to destination from here"
    // bundle status report reason code.
    NoRouteToDestination(6),

    // NoNextNodeContact is the "No timely contact with next node on route" bundle
    // status report reason code.
    NoNextNodeContact(7),

    // BlockUnintelligible is the "Block unintelligible" bundle status report
    // reason code.
    BlockUnintelligible(8),

    // HopLimitExceeded is the "Hop limit exceeded" bundle status report reason
    // code.
    HopLimitExceeded(9),

    // TrafficPared is the "Traffic pared (e.g., status reports)" bundle status
    // report reason code.
    TrafficPared(10),

    // BlockUnsupported is the "Block unsupported" bundle status report reason
    // code.
    BlockUnsupported(11);

    override fun toString(): String {
        return this.name + "($code)"
    }
}

fun statusRecord(
    bundle: Bundle,
    assertion: StatusAssertion,
    reason: StatusReportReason,
    time: DtnTime = dtnTimeNow()
): AdministrativeRecord {
    return AdministrativeRecord(
        recordTypeCode = RecordTypeCode.StatusRecordType.code,
        data = statusReport(bundle)
            .assert(assertion, true, time)
            .reason(reason)
    )
}

fun StatusReport.assert(status: StatusAssertion, assert: Boolean, time: DtnTime): StatusReport {
    assertedStatuses = if (assert) assertedStatuses + status else assertedStatuses - status
    val assertedTime = if (assert) time else 0
    when (status) {
        StatusAssertion.ReceivedBundle -> received = assertedTime
        StatusAssertion.ForwardedBundle -> forwarded = assertedTime
        StatusAssertion.DeliveredBundle -> delivered = assertedTime
        StatusAssertion.DeletedBundle -> deleted = assertedTime
    }
    return this
}

fun StatusReport.reason(reason: StatusReportReason): StatusReport {
    bundleStatusReportReason = reason.code
    return this
}

fun StatusReport.reportedId() : String {
    return UUID.nameUUIDFromBytes(
        (sourceNodeId.toASCIIString()
                + creationTimestamp
                + sequenceNumber
                + isFragment
                + fragmentOffset
                + appDataLength)
            .toByteArray()).toString()
}

fun StatusReport.assertion() : String {
    val sb = StringBuilder("");
    if(received > 0 || StatusAssertion.ReceivedBundle in assertedStatuses) {
        sb.append("${if (sb.isNotEmpty()) "| " else ""}RECEIVED ")
    }
    if(forwarded > 0 || StatusAssertion.ForwardedBundle in assertedStatuses) {
        sb.append("${if (sb.isNotEmpty()) "| " else ""}FORWARDED ")
    }
    if(delivered > 0 || StatusAssertion.DeliveredBundle in assertedStatuses) {
        sb.append("${if (sb.isNotEmpty()) "| " else ""}DELIVERED ")
    }
    if(deleted > 0 || StatusAssertion.DeletedBundle in assertedStatuses) {
        sb.append("${if (sb.isNotEmpty()) "| " else ""}DELETED ")
    }
    return sb.toString()
}
