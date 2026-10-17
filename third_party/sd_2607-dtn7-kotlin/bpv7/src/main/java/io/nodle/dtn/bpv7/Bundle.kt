package io.nodle.dtn.bpv7

import io.nodle.dtn.bpv7.administrative.StatusReport
import io.nodle.dtn.bpv7.administrative.cborUnmarshalAdmnistrativeRecord

/**
 * @author Lucien Loiseau on 12/02/21.
 */

data class Bundle(
    var primaryBlock: PrimaryBlock,
    var canonicalBlocks: MutableList<CanonicalBlock> = ArrayList()
)

fun Bundle.addBlock(block: CanonicalBlock, pickNumber: Boolean = true): Bundle {
    if (block.blockType != BlockType.PayloadBlock.code || !hasBlockType(BlockType.PayloadBlock.code)) {
        if (pickNumber) {
            if (block.blockType == BlockType.PayloadBlock.code) {
                block.number(1)
            } else {
                val highest = maxOf(1, canonicalBlocks.maxOfOrNull { it.blockNumber } ?: 1)
                var next = if (highest < Int.MAX_VALUE) highest + 1 else 2
                while (hasBlockNumber(next)) {
                    require(next < Int.MAX_VALUE) { "no block number available" }
                    next++
                }
                block.number(next)
            }
        }
        if (block.blockType == BlockType.PayloadBlock.code) {
            canonicalBlocks.add(block)
        } else {
            val payloadIndex = canonicalBlocks.indexOfFirst { it.blockType == BlockType.PayloadBlock.code }
            canonicalBlocks.add(if (payloadIndex < 0) canonicalBlocks.size else payloadIndex, block)
        }
    }
    return this
}

fun Bundle.isAdminRecord() = primaryBlock.isAdminRecord()

fun Bundle.hasBlockType(blockType: BlockType) = hasBlockType(blockType.code)

fun Bundle.hasBlockType(blockType: Int) = canonicalBlocks.any { it.blockType == blockType }

fun Bundle.hasBlockNumber(blockNumber: Int) = canonicalBlocks.any { it.blockNumber == blockNumber }

fun Bundle.getBlockType(blockType: Int) = canonicalBlocks.firstOrNull { it.blockType == blockType }

fun Bundle.getBlockNumber(blockNumber: Int) = canonicalBlocks.firstOrNull { it.blockNumber == blockNumber }

fun Bundle.getPayloadBlock() = getBlockType(BlockType.PayloadBlock.code)!!

fun Bundle.getStatusReport(): StatusReport? {
    if (primaryBlock.isAdminRecord()) {
        return getPayloadBlockData().buffer.run {
            cborUnmarshalAdmnistrativeRecord(this).data as StatusReport
        }
    }
    return null
}

fun Bundle.ID() = primaryBlock.ID()
fun Bundle.fragmentedID() = primaryBlock.fragmentedID()
