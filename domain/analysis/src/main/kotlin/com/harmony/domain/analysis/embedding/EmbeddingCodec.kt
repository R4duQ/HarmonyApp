package com.harmony.domain.analysis.embedding

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * FloatArray <-> ByteArray for Room blob storage. Little-endian, fixed-width:
 * loading 20k embeddings at startup is a straight bulk copy, no parsing.
 */
object EmbeddingCodec {

    fun encode(embedding: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(embedding.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.asFloatBuffer().put(embedding)
        return buffer.array()
    }

    fun decode(blob: ByteArray): FloatArray {
        val floats = FloatArray(blob.size / 4)
        ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats)
        return floats
    }
}
