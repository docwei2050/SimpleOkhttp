package com.docwei.okhttp


import android.util.Log
import closeQuietly
import com.docwei.okhttp.concurrent.TaskRunner
import com.docwei.okhttp.http2.Http2Connection
import com.docwei.okhttp.http2.Http2ExchangeCodec
import com.docwei.okhttp.http2.Settings
import concat
import immutableListOf
import indexOf
import intersect
import okhttp3.CipherSuite
import okio.*
import toHostHeader
import java.net.*
import java.nio.charset.StandardCharsets
import javax.net.ssl.SSLSocket

private const val HEADER_LIMIT = 256 * 1024
//16M
const val OKHTTP_CLIENT_WINDOW_SIZE = 16 * 1024 * 1024

class RealCall2 constructor(var okHttpClient: OkHttpClient, var request: Request) : Call {
    private var rawSocket: Socket? = null
    private var source: BufferedSource? = null
    private var sink: BufferedSink? = null
    private var headerLimit = HEADER_LIMIT.toLong()
    val okHttpSettings = Settings().apply {
        // Flow control was designed more for servers, or proxies than edge clients. If we are a client,
        // set the flow control window to 16MiB.  This avoids thrashing window updates every 64KiB, yet
        // small enough to avoid blowing up the heap.
        set(Settings.INITIAL_WINDOW_SIZE, OKHTTP_CLIENT_WINDOW_SIZE)
    }

    //承载一个同步执行一个异步执行的方法
    fun execute(): Response {
        return startRequest()
    }

    fun enqueue(callback: Callback) {
        //需要在Dispatcher的线程池中去执行任务
        okHttpClient.dispatcher.executorService.execute {
            try {
                var response = startRequest()
                if (response != null) {
                    callback.onResponse(this, response)
                }
            } catch (e: IOException) {
                callback.onFailure(this, e)
            }

        }
    }

    private fun startRequest(): Response {
        var success = false
        var sslSocket: SSLSocket? = null
        try {
            //stpe1:先做桥接拦截器的操作，改造一下请求体 让普通请求支持gzip
            var transparentGzip = performBridgeInterceptor()
            //step2:走连接拦截器的操作 创建连接
            //一个RealConnection对应一个route
            //基于host去查找 可能会返回多个InetAddress
            //如果基于这个InetAddress创建的连接失败，就去尝试用下一个InetAddress
            var addresses = Dns.SYSTEM.lookup(request.url.host)
            //www.baidu.com/182.61.200.6   www.baidu.com/182.61.200.7
            if (addresses.isEmpty()) {
                throw UnknownHostException("${Dns.SYSTEM} returned no addresses for ${request.url.host}")
            }
            //step3:这里处于方便，只选择第一个address去创建socket ---rawSocket
            //SocketFactory.getDefault()使用默认的SocketFactory去创建工厂
            val rawSocket = okHttpClient.socketFactory.createSocket()!!
            this.rawSocket = rawSocket
            var socketPort = request.url.port
            if (socketPort !in 1..65535) {
                throw SocketException("No route to ${request.url.host}:$socketPort; port is out of range")
            }
            //这里应该是区分系统的，android10跟其他android版本处理稍有不同，这里以android 10为准
            rawSocket.connect(InetSocketAddress(addresses[0], socketPort), 10_000)
            source = rawSocket.source().buffer()
            sink = rawSocket.sink().buffer()
            Log.e("okhttp", "${addresses[0]}")
            //step4:创建Tls连接 握手   加一层ssl
            sslSocket = okHttpClient.sslSocketFactory!!.createSocket(
                rawSocket,
                request.url.host, request.url.port, true
            ) as SSLSocket
            //与服务器协商时，需要给sslSocket设置共同的配置
            //客户端支持 TLSv1.3 TLSv1.2
            var tlsConnectionSpec: ConnectionSpec = ConnectionSpec.MODERN_TLS
            for (connectionspec in okHttpClient.connectionSpec) {
                if (connectionspec.isCompatible(sslSocket)) {
                    tlsConnectionSpec = connectionspec;
                    break
                }
            }
            //将sslSocket支持的protocols与cihersuites 和 这个TlsConnectionSpec的取交集，再赋值给sslSocket
            val cipherSuitesAsString =
                tlsConnectionSpec.cipherSuites!!.map { it.javaName }!!.toTypedArray()
            var cipherSuitesIntersection = if (tlsConnectionSpec.cipherSuites != null) {
                sslSocket.enabledCipherSuites.intersect(
                    cipherSuitesAsString,
                    CipherSuite.ORDER_BY_NAME
                )
            } else {
                sslSocket.enabledCipherSuites
            }
            val tlsVersionsAsString =
                tlsConnectionSpec.tlsVersions!!.map { it.javaName }.toTypedArray()
            val tlsVersionIntersection = if (tlsVersionsAsString != null) {
                sslSocket.enabledProtocols.intersect(tlsVersionsAsString, naturalOrder())
            } else {
                sslSocket.enabledProtocols
            }
            //TLS_FALLBACK_SCSV 信令套件可以用来阻止客户端和服务器之间的意外降级，预防中间人攻击。
            //https://yryz.net/post/tls-fallback-scsv/
            val supportedCipherSuites = sslSocket.supportedCipherSuites
            val indexOfFallbackScsv = supportedCipherSuites.indexOf(
                "TLS_FALLBACK_SCSV", CipherSuite.ORDER_BY_NAME
            )
            if (indexOfFallbackScsv != -1) {
                cipherSuitesIntersection =
                    cipherSuitesIntersection.concat(supportedCipherSuites[indexOfFallbackScsv])
            }
            sslSocket.enabledProtocols = tlsVersionIntersection
            sslSocket.enabledCipherSuites = cipherSuitesIntersection
            val sslSocketClass =
                Class.forName("com.android.org.conscrypt.OpenSSLSocketImpl") as Class<in SSLSocket>
            val setAlpnProtocols =
                sslSocketClass.getMethod("setAlpnProtocols", ByteArray::class.java)
            //握手前配置 configureTlsExtensions
            var protocols = immutableListOf(Protocol.HTTP_1_1, Protocol.HTTP_2)
            setAlpnProtocols.invoke(sslSocket, concatLengthPrefixed(protocols))

            //开始握手
            sslSocket.startHandshake()

            source = sslSocket.source().buffer()
            sink = sslSocket.sink().buffer()
            success = true

            sslSocket.soTimeout = 0 // HTTP/2 connection timeouts are set per-stream.
            val sink = sink!!
            val source = source!!
            //超时时间设置在连接的每一个流上

            //可以在这里判断是走的http1.1还是http2.0(h2)
            val getAlpnSelectedProtocol = sslSocketClass.getMethod("getAlpnSelectedProtocol")
            val alpnResult = getAlpnSelectedProtocol.invoke(sslSocket) as ByteArray?
            val protocolStr = if (alpnResult != null) String(alpnResult, StandardCharsets.UTF_8) else null
            //默认是http1.1
            val protocol = if (protocolStr != null) Protocol.get(protocolStr) else Protocol.HTTP_1_1

            if (protocol == Protocol.HTTP_2) {
                var http2Connection =
                    Http2Connection.Builder(client = true, taskRunner = TaskRunner.INSTANCE)
                        .socket(sslSocket, request.url.host, source, sink)
                        .pingIntervalMillis(0)
                        .build()
                //首先要发送一个连接序言，确认双方使用http2.0，这是http直连逻辑
                //试探是否能升级http2.0需要依赖服务端返回code=101
                http2Connection.start()

                //写请求头
                val exchangeCodec = Http2ExchangeCodec(http2Connection)
                exchangeCodec.writeRequestHeaders(request)
                //step6: 开始写请求体
                //get请求和head请求不能有请求体
                if (HttpMethod.permitsRequestBody(request.method) && request.body != null) {
                    //Expected:100-continue 请求头不支持
                    //请求体默认不支持isDuplex
                    val contentLength = request.body!!.contentLength()
                    val rawRequestBody = exchangeCodec.createRequestBody(request)
                    var bufferedSink = RequestBodySink(rawRequestBody, contentLength).buffer()
                    request!!.body!!.writeTo(bufferedSink)
                    bufferedSink.close()

                } else {
                    sink.flush()
                }

                exchangeCodec.finishRequest()


                var response = exchangeCodec.readResponseHeaders(false)!!
                    .request(request)
                    .build()

                val contentType = response.header("Content-Type")
                val contentLength = exchangeCodec.reportedContentLength(response)
                val rawSource = exchangeCodec.openResponseBodySource(response)
                val source = Http1ExchangeCodec.ResponseBodySource(rawSource, contentLength)
                var networkResponse = response.newBuilder()
                    .body(RealResponseBody(contentType, contentLength, source.buffer()))
                    .build()

                var responseBuilder = networkResponse.newBuilder()
                //如果走的gzip压缩，那么就需要解压gzip的流
                //step9: 流解压
                if (transparentGzip
                    && "gzip".equals(networkResponse.header("Content-Encoding"), ignoreCase = true)
                    && networkResponse.promisesBody()
                ) {
                    val responseBody = networkResponse.body
                    if (responseBody != null) {
                        val gzipSource = GzipSource(responseBody.source())
                        val strippedHeaders = networkResponse.headers.newBuilder()
                            .removeAll("Content-Encoding")
                            .removeAll("Content-Length")
                            .build()
                        responseBuilder.headers(strippedHeaders)
                        val contentType = networkResponse.header("Content-Type")
                        responseBuilder.body(
                            RealResponseBody(
                                contentType, -1L, gzipSource.buffer()
                            )
                        )
                    }
                }
                return responseBuilder.build()
            }
        } catch (e: IOException) {
            sslSocket?.closeQuietly()
            rawSocket?.close()
            rawSocket = null
            source = null
            sink = null
        } finally {
            if (!success) {
                sslSocket?.closeQuietly()
            }
        }
        return Response.Builder().build()
    }

    private fun performBridgeInterceptor(): Boolean {
        var requestbuilder = request.newBuilder()
        var body = request.body
        if (body != null) {
            var contentType = body.contentType()
            Log.e("okhttp", "contentType---${contentType.toString()}")
            if (contentType != null) {
                requestbuilder.header("Content-Type", contentType.toString())
            }
            var contentLength = body.contentLength()
            if (contentLength != -1L) {
                requestbuilder.header("Content-Length", contentLength.toString())
                requestbuilder.removeHeader("Transfer-Encoding")
            } else {
                requestbuilder.header("Transfer-Encoding", "chunked")
                requestbuilder.removeHeader("Content-Length")
            }
        }

        if (request.header("Host") == null) {
            requestbuilder.header("Host", request.url.toHostHeader())
        }
        if (request.header("Connection") == null) {
            requestbuilder.header("Connection", "Keep-Alive")
        }

        var transParentGizp = false
        if (request.header("Accept-Encoding") == null
            && request.header("Range") == null
        ) {
            transParentGizp = true
            requestbuilder.header("Accept-Encoding", "gzip")
        }

        if (request.header("User-Agent") == null) {
            requestbuilder.header("User-Agent", "okhttp-docwei")
        }
        request = requestbuilder.build();
        return transParentGizp
    }

    private fun readHeaderLine(source: BufferedSource): String {
        val line = source!!.readUtf8LineStrict(headerLimit)
        headerLimit -= line.length.toLong()
        return line
    }


    companion object {
        fun newRealCall(client: OkHttpClient, originalRequest: Request): RealCall2 {
            // Safely publish the Call instance to the EventListener.
            return RealCall2(client, originalRequest)
        }

    }

    /** A request body that fires events when it completes. */
    private inner class RequestBodySink internal constructor(
        delegate: Sink,
        /** The exact number of bytes to be written, or -1L if that is unknown. */
        private val contentLength: Long
    ) : ForwardingSink(delegate) {
        private var completed = false
        private var bytesReceived = 0L
        private var closed = false

        @Throws(java.io.IOException::class)
        override fun write(source: Buffer, byteCount: Long) {
            check(!closed) { "closed" }
            if (contentLength != -1L && bytesReceived + byteCount > contentLength) {
                throw ProtocolException(
                    "expected $contentLength bytes but received ${bytesReceived + byteCount}"
                )
            }
            try {
                super.write(source, byteCount)
                this.bytesReceived += byteCount
            } catch (e: java.io.IOException) {
                throw e
            }
        }

        @Throws(java.io.IOException::class)
        override fun flush() {
            try {
                super.flush()
            } catch (e: java.io.IOException) {
                throw e
            }
        }

        @Throws(java.io.IOException::class)
        override fun close() {
            if (closed) return
            closed = true
            if (contentLength != -1L && bytesReceived != contentLength) {
                throw ProtocolException("unexpected end of stream")
            }
            try {
                super.close()

            } catch (e: java.io.IOException) {
                Log.e("okhttp", e.message)
                throw e
            }
        }


    }

    /**
     * Returns the concatenation of 8-bit, length prefixed protocol names.
     * http://tools.ietf.org/html/draft-agl-tls-nextprotoneg-04#page-4
     */
    fun concatLengthPrefixed(protocols: List<Protocol>): ByteArray {
        val result = Buffer()
        for (protocol in alpnProtocolNames(protocols)) {
            result.writeByte(protocol.length)
            result.writeUtf8(protocol)
        }
        return result.readByteArray()
    }

    fun alpnProtocolNames(protocols: List<Protocol>) =
        protocols.map { it.toString() }
}