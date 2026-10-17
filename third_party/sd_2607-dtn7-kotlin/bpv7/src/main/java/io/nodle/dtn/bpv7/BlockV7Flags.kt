package io.nodle.dtn.bpv7

import io.nodle.dtn.utils.isFlagSet

/**
 * @author Lucien Loiseau on 12/02/21.
 */
enum class BlockV7Flags(val offset : Int) {
    // RFC 9171 section 4.2.4, counted from the least-significant bit.
    ReplicateInEveryFragment(0),
    StatusReportIfNotProcessed(1),
    DeleteBundleIfNotProcessed(2),
    Reserved3(3),
    DiscardIfNotProcessed(4),
    Reserved5(5),
    @Deprecated("BPv7 has no encrypted-block control flag; encryption is indicated by a BCB")
    BlockIsEncrypted(6),  // not in RFC
    Reserved7(7)
}

fun CanonicalBlock.isFlagSet(flag: BlockV7Flags) : Boolean =
        procV7flags.isFlagSet(flag.offset)