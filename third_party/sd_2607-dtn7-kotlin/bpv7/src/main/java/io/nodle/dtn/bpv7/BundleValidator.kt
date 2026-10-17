package io.nodle.dtn.bpv7

import io.nodle.dtn.bpv7.administrative.StatusReportReason
import io.nodle.dtn.bpv7.bpsec.*
import io.nodle.dtn.bpv7.eid.*
import io.nodle.dtn.bpv7.extensions.getAgeBlockData
import io.nodle.dtn.bpv7.extensions.BundleAgeBlockData
import io.nodle.dtn.bpv7.extensions.HopCountBlockData
import io.nodle.dtn.bpv7.extensions.PreviousNodeBlockData

/**
 * @author Lucien Loiseau on 14/02/21.
 */
class ValidationException(val msg: String, val status: StatusReportReason = StatusReportReason.NoInformation) : Exception(msg)

fun Bundle.isValid(): Boolean = try {
    checkValid()
    true
} catch (e: ValidationException) {
    false
}

@Throws(ValidationException::class)
fun Bundle.checkValid() {
    checkValidPrimaryBlock()

    for (block in canonicalBlocks) {
        checkValidCanonicalBlock(block)
    }

    if (!primaryBlock.hasCRC() && !isSignedWithEd25519(0)) {
        throw ValidationException("bundle:${ID()} - primary block must have a CRC or being the target of a BIB")
    }

    if (canonicalBlocks.count { it.blockType == BlockType.PayloadBlock.code } != 1) {
        throw ValidationException("bundle:${ID()} - the bundle must have exactly one payload block")
    }

    if (canonicalBlocks.last().blockType != BlockType.PayloadBlock.code) {
        throw ValidationException("bundle:${ID()} - the last block must be a payload block")
    }

    if (canonicalBlocks.map { it.blockNumber }.distinct().size < canonicalBlocks.size) {
        throw ValidationException("bundle:${ID()} - multiple blocks with same blockNumber")
    }

    for (type in listOf(BlockType.PreviousNodeBlock, BlockType.BundleAgeBlock, BlockType.HopCountBlock)) {
        if (canonicalBlocks.count { it.blockType == type.code } > 1) {
            throw ValidationException("bundle:${ID()} - multiple $type blocks")
        }
    }

    checkLifetimeExceeded()
}


@Throws(ValidationException::class)
fun Bundle.checkValidPrimaryBlock() {
    if (primaryBlock.version != 7 || primaryBlock.creationTimestamp < 0 ||
        primaryBlock.sequenceNumber < 0 || primaryBlock.lifetime < 0 ||
        primaryBlock.fragmentOffset < 0 || primaryBlock.appDataLength < 0
    ) {
        throw ValidationException("bundle:${ID()} - invalid primary fields", StatusReportReason.BlockUnintelligible)
    }
    if (isAdminRecord() && listOf(
            BundleV7Flags.StatusRequestReception, BundleV7Flags.StatusRequestForward,
            BundleV7Flags.StatusRequestDelivery, BundleV7Flags.StatusRequestDeletion
        ).any { isFlagSet(it) }
    ) {
        throw ValidationException("administrative bundles must not request status reports")
    }
    if (isFlagSet(BundleV7Flags.IsFragment) && isFlagSet(BundleV7Flags.MustNotFragment)) {
        throw ValidationException("bundle:${ID()} - primary: bundle is a fragment but must_no_fragment flag is set")
    }

    try {
        primaryBlock.source.checkValidEid()
        primaryBlock.reportTo.checkValidEid()
    } catch (e: InvalidEid) {
        throw ValidationException("bundle:${ID()} - primary: eid unintelligible")
    }

    try {
        primaryBlock.destination.checkValidEid()
    } catch (e: InvalidEid) {
        throw ValidationException(
            "bundle:${ID()} - primary: destination eid unintelligible",
            status = StatusReportReason.DestEndpointUnintelligible
        )
    }

    if (primaryBlock.source.isNullEid() && !(isFlagSet(BundleV7Flags.MustNotFragment)
                && !isFlagSet(BundleV7Flags.StatusRequestReception)
                && !isFlagSet(BundleV7Flags.StatusRequestForward)
                && !isFlagSet(BundleV7Flags.StatusRequestDelivery)
                && !isFlagSet(BundleV7Flags.StatusRequestDeletion))
    ) {
        throw ValidationException("bundle:${ID()} - primary: anonymous bundle shall not be fragmented and status report not delivered")
    }
}

@Throws(ValidationException::class)
fun Bundle.checkLifetimeExceeded() {
    if ((this.primaryBlock.creationTimestamp == 0L) && !this.hasBlockType(BlockType.BundleAgeBlock)) {
        throw ValidationException("bundle:${ID()} - primary: creatimeTimestamp is zero but bundle has no age block")
    }

    if (isExpired()) {
        throw ValidationException(
            "bundle:${ID()} - primary: bundle has expired (${(dtnTimeNow() - expireAt())/1000} seconds ago)",
            StatusReportReason.LifetimeExpired
        )
    }
}

fun Bundle.expireAt(): DtnTime =
    if (this.primaryBlock.creationTimestamp == 0L) {
        saturatedTimeAdd(dtnTimeNow(), primaryBlock.lifetime - getAgeBlockData()!!.age)
    } else {
        saturatedTimeAdd(primaryBlock.creationTimestamp, primaryBlock.lifetime)
    }

fun Bundle.isExpired(now : Long = dtnTimeNow()): Boolean =
    if (this.primaryBlock.creationTimestamp == 0L) {
        getAgeBlockData()!!.age > this.primaryBlock.lifetime
    } else {
        now > saturatedTimeAdd(primaryBlock.creationTimestamp, primaryBlock.lifetime)
    }

internal fun saturatedTimeAdd(time: Long, delta: Long): Long =
    if (delta > 0 && time > Long.MAX_VALUE - delta) Long.MAX_VALUE
    else if (delta < 0 && time < Long.MIN_VALUE - delta) Long.MIN_VALUE
    else time + delta

@Throws(ValidationException::class)
fun Bundle.checkValidCanonicalBlock(block: CanonicalBlock) {
    if (block.blockNumber <= 0 || block.blockType <= 0) {
        throw ValidationException("bundle:${ID()} - canonical block type and number must be positive")
    }
    if ((isAdminRecord() || primaryBlock.source.isNullEid()) &&
        block.isFlagSet(BlockV7Flags.StatusReportIfNotProcessed)
    ) {
        throw ValidationException("administrative/anonymous bundles must not request block status reports")
    }
    when (block.blockType) {
        BlockType.PayloadBlock.code -> if (block.blockNumber != 1 || block.data !is BlobBlockData) {
            throw ValidationException("payload block must have number 1 and raw data")
        }
        BlockType.BundleAgeBlock.code -> if ((block.data as? BundleAgeBlockData)?.age?.let { it >= 0 } != true) {
            throw ValidationException("invalid bundle age block")
        }
        BlockType.HopCountBlock.code -> {
            val hop = block.data as? HopCountBlockData ?: throw ValidationException("invalid hop count block")
            if (hop.limit !in 1..255 || hop.count < 0) throw ValidationException("invalid hop limit/count")
        }
        BlockType.PreviousNodeBlock.code -> {
            val previous = block.data as? PreviousNodeBlockData ?: throw ValidationException("invalid previous node block")
            try {
                previous.previous.checkValidEid()
            } catch (e: InvalidEid) {
                throw ValidationException("invalid previous node EID")
            }
        }
        BlockType.BlockIntegrityBlock.code -> block.checkValidBIB(this)
    }
}
