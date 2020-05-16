package com.docwei.okhttp

import closeQuietly
import okio.Buffer
import okio.BufferedSource
import okio.ByteString
import java.io.*
import java.nio.charset.Charset
import com.docwei.okhttp.MediaType.Companion.toMediaTypeOrNull
import readBomAsCharset


abstract class ResponseBody : Closeable {
    private var reader: Reader? = null;
    abstract fun contentType(): MediaType?
    abstract fun contentLength(): Long
    fun byteStream(): InputStream = source().inputStream()
    abstract fun source(): BufferedSource


    fun bytes() = consumeSource(BufferedSource::readByteArray) {
        it.size
    }

    fun byteString() = consumeSource(BufferedSource::readByteString) {
        it.size
    }

    private inline fun <T : Any> consumeSource(
        consumer: (BufferedSource) -> T, sizeMapper: (T) -> Int
    ): T {
        val contentLength = contentLength()
        if (contentLength > Int.MAX_VALUE) {
            throw IOException("Cannot buffer entire body for content length: $contentLength")
        }
        val bytes = source().use(consumer)
        val size = sizeMapper(bytes)
        if (contentLength != -1L && contentLength != size.toLong()) {
            throw IOException("Content-Length ($contentLength) and stream length ($size) disagree")
        }
        return bytes
    }

    fun charStream(): Reader = reader ?: BomAwareReader(source(), charset()).also {
        reader = it
    }

    @Throws(IOException::class)
    fun string(): String = source().use { source ->
        source.readString(charset = source.readBomAsCharset(charset()))
    }

    private fun charset() = contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8

    override fun close() = source().closeQuietly()


    internal class BomAwareReader(
        private val source: BufferedSource,
        private val charset: Charset
    ) : Reader() {

        private var closed: Boolean = false
        private var delegate: Reader? = null

        @Throws(IOException::class)
        override fun read(cbuf: CharArray, off: Int, len: Int): Int {
            if (closed) throw IOException("Stream closed")

            val finalDelegate = delegate ?: InputStreamReader(
                source.inputStream(),
                source.readBomAsCharset(charset)
            ).also {
                delegate = it
            }
            return finalDelegate.read(cbuf, off, len)
        }

        @Throws(IOException::class)
        override fun close() {
            closed = true
            delegate?.close() ?: run { source.close() }
        }
    }

    companion object {
        /**
         * Returns a new response body that transmits this string. If [contentType] is non-null and
         * lacks a charset, this will use UTF-8.
         */
        @JvmStatic
        @JvmName("create")
        fun String.toResponseBody(contentType: MediaType? = null): ResponseBody {
            var charset: Charset = Charsets.UTF_8
            var finalContentType: MediaType? = contentType
            if (contentType != null) {
                val resolvedCharset = contentType.charset()
                if (resolvedCharset == null) {
                    charset = Charsets.UTF_8
                    finalContentType = "$contentType; charset=utf-8".toMediaTypeOrNull()
                } else {
                    charset = resolvedCharset
                }
            }
            val buffer = Buffer().writeString(this, charset)
            return buffer.asResponseBody(finalContentType, buffer.size)
        }

        /** Returns a new response body that transmits this byte array. */
        @JvmStatic
        @JvmName("create")
        fun ByteArray.toResponseBody(contentType: MediaType? = null): ResponseBody {
            return Buffer()
                .write(this)
                .asResponseBody(contentType, size.toLong())
        }

        /** Returns a new response body that transmits this byte string. */
        @JvmStatic
        @JvmName("create")
        fun ByteString.toResponseBody(contentType: MediaType? = null): ResponseBody {
            return Buffer()
                .write(this)
                .asResponseBody(contentType, size.toLong())
        }

        /** Returns a new response body that transmits this source. */
        @JvmStatic
        @JvmName("create")
        fun BufferedSource.asResponseBody(
            contentType: MediaType? = null,
            contentLength: Long = -1L
        ): ResponseBody = object : ResponseBody() {
            override fun contentType() = contentType

            override fun contentLength() = contentLength

            override fun source() = this@asResponseBody
        }
    }
}