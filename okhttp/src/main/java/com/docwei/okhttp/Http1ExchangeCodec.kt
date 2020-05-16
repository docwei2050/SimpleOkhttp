package com.docwei.okhttp

import headersContentLength
import okio.*
import java.io.IOException
import java.net.ProtocolException

class Http1ExchangeCodec internal constructor(
    private val source: BufferedSource
) {
    private fun Response.isChunked() =
        "chunked".equals(header("Transfer-Encoding"), ignoreCase = true)


    private abstract inner class AbstractSource : Source {
        protected val timeout = ForwardingTimeout(source.timeout())
        protected var closed: Boolean = false

        override fun timeout(): Timeout = timeout

        override fun read(sink: Buffer, byteCount: Long): Long {
            return try {
                source.read(sink, byteCount)
            } catch (e: IOException) {
                throw e
            }
        }


    }

    /** An HTTP body with a fixed length specified in advance. */
   private  inner class FixedLengthSource internal constructor(private var bytesRemaining: Long) :
        AbstractSource() {


        override fun read(sink: Buffer, byteCount: Long): Long {
            require(byteCount >= 0L) { "byteCount < 0: $byteCount" }
            check(!closed) { "closed" }
            if (bytesRemaining == 0L) return -1

            val read = super.read(sink, minOf(bytesRemaining, byteCount))

            bytesRemaining -= read
            if (bytesRemaining == 0L) {
                //读取响应完成
            }
            return read
        }

        override fun close() {
            if (closed) return


            closed = true
        }
    }

    /** An HTTP body with alternating chunk sizes and chunk bodies. */
    private inner class ChunkedSource internal constructor(private val url: HttpUrl) :
        AbstractSource() {
        private var bytesRemainingInChunk = NO_CHUNK_YET
        private var hasMoreChunks = true

        override fun read(sink: Buffer, byteCount: Long): Long {
            require(byteCount >= 0L) { "byteCount < 0: $byteCount" }
            check(!closed) { "closed" }
            if (!hasMoreChunks) return -1

            if (bytesRemainingInChunk == 0L || bytesRemainingInChunk == NO_CHUNK_YET) {
                readChunkSize()
                if (!hasMoreChunks) return -1
            }

            val read = super.read(sink, minOf(byteCount, bytesRemainingInChunk))
            bytesRemainingInChunk -= read
            return read
        }

        private fun readChunkSize() {
            // Read the suffix of the previous chunk.
            if (bytesRemainingInChunk != NO_CHUNK_YET) {
                source.readUtf8LineStrict()
            }
            try {
                bytesRemainingInChunk = source.readHexadecimalUnsignedLong()
                val extensions = source.readUtf8LineStrict().trim()
                if (bytesRemainingInChunk < 0L || extensions.isNotEmpty() && !extensions.startsWith(
                        ";"
                    )
                ) {
                    throw ProtocolException(
                        "expected chunk size and optional extensions" +
                                " but was \"$bytesRemainingInChunk$extensions\""
                    )
                }
            } catch (e: NumberFormatException) {
                throw ProtocolException(e.message)
            }

            if (bytesRemainingInChunk == 0L) {
                hasMoreChunks = false
            }
        }

        override fun close() {
            if (closed) return
            closed = true
        }
    }

    /** An HTTP message body terminated by the end of the underlying stream. */
    private inner class UnknownLengthSource : AbstractSource() {
        private var inputExhausted: Boolean = false

        override fun read(sink: Buffer, byteCount: Long): Long {
            require(byteCount >= 0L) { "byteCount < 0: $byteCount" }
            check(!closed) { "closed" }
            if (inputExhausted) return -1

            val read = super.read(sink, byteCount)
            if (read == -1L) {
                inputExhausted = true
                return -1
            }
            return read
        }

        override fun close() {
            if (closed) return
            if (!inputExhausted) {
            }
            closed = true
        }
    }
    fun openResponseBodySource(response: Response): Source {
        return when {
            !response.promisesBody() -> FixedLengthSource(0)
            response.isChunked() -> ChunkedSource(response.request.url)
            else -> {
                val contentLength = response.headersContentLength()
                if (contentLength != -1L) {
                   FixedLengthSource(contentLength)
                } else {
                    UnknownLengthSource()
                }
            }
        }
    }

   class ResponseBodySource(
        delegate: Source,
        private val contentLength: Long
    ) : ForwardingSource(delegate) {
        private var bytesReceived = 0L
        private var completed = false
        private var closed = false

        init {
            if (contentLength == 0L) {
                complete(null)
            }
        }

        @Throws(IOException::class)
        override fun read(sink: Buffer, byteCount: Long): Long {
            check(!closed) { "closed" }
            try {
                val read = delegate.read(sink, byteCount)
                if (read == -1L) {
                    complete(null)
                    return -1L
                }

                val newBytesReceived = bytesReceived + read
                if (contentLength != -1L && newBytesReceived > contentLength) {
                    throw ProtocolException("expected $contentLength bytes but received $newBytesReceived")
                }

                bytesReceived = newBytesReceived
                if (newBytesReceived == contentLength) {
                    complete(null)
                }

                return read
            } catch (e: IOException) {
                throw e
            }
        }

        @Throws(IOException::class)
        override fun close() {
            if (closed) return
            closed = true
            try {
                super.close()
                complete(null)
            } catch (e: IOException) {
                throw complete(e)
            }
        }

        fun <E : IOException?> complete(e: E): E {
            if (completed) return e
            completed = true
            return e
        }
    }




    companion object {
        private const val NO_CHUNK_YET = -1L

        private const val STATE_IDLE = 0 // Idle connections are ready to write request headers.
        private const val STATE_OPEN_REQUEST_BODY = 1
        private const val STATE_WRITING_REQUEST_BODY = 2
        private const val STATE_READ_RESPONSE_HEADERS = 3
        private const val STATE_OPEN_RESPONSE_BODY = 4
        private const val STATE_READING_RESPONSE_BODY = 5
        private const val STATE_CLOSED = 6
        private const val HEADER_LIMIT = 256 * 1024
    }
}