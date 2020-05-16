package com.docwei.okhttp


import android.util.Log
import closeQuietly
import concat
import headersContentLength
import indexOf
import intersect
import okhttp3.CipherSuite
import okhttp3.internal.http.RequestLine
import okhttp3.internal.http.StatusLine
import okio.*
import toHostHeader
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket

private const val HEADER_LIMIT = 256 * 1024

class RealCall constructor(var okHttpClient: OkHttpClient, var request: Request) {
    private var rawSocket: Socket? = null
    private var source: BufferedSource? = null
    private var sink: BufferedSink? = null
    private var headerLimit = HEADER_LIMIT.toLong()
    //承载一个同步执行一个异步执行的方法
    fun execute(): Response {
        return startRequest()
    }
    fun enqueue(callback:Callback) {
        //需要在Dispatcher的线程池中去执行任务
        okHttpClient.dispatcher.executorService.execute {
            try {
                var response = startRequest()
                if(response!=null){
                    callback.onResponse(this,response)
                }
            }catch (e:IOException){
                callback.onFailure(this,e)
            }

        }
    }

    private fun startRequest(): Response {
        var success =false
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

            //step4:创建Tls连接 握手   加一层ssl
            sslSocket = okHttpClient.sslSocketFactory!!.createSocket(rawSocket,
                request.url.host, request.url.port, true) as SSLSocket
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
            val cipherSuitesAsString = tlsConnectionSpec.cipherSuites!!.map { it.javaName }!!.toTypedArray()
            var cipherSuitesIntersection = if (tlsConnectionSpec.cipherSuites != null) {
                sslSocket.enabledCipherSuites.intersect(cipherSuitesAsString,CipherSuite.ORDER_BY_NAME)
            } else {
                sslSocket.enabledCipherSuites
            }
            val tlsVersionsAsString = tlsConnectionSpec.tlsVersions!!.map { it.javaName }.toTypedArray()
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
                cipherSuitesIntersection = cipherSuitesIntersection.concat(supportedCipherSuites[indexOfFallbackScsv])
            }
            sslSocket.enabledProtocols = tlsVersionIntersection
            sslSocket.enabledCipherSuites = cipherSuitesIntersection


           /* val socketFactory =
                SSLCertificateSocketFactory.getDefault(10_000) as SSLCertificateSocketFactory
            socketFactory.setUseSessionTickets(sslSocket, true)*/


            //开始握手
            sslSocket.startHandshake()

            source = sslSocket.source().buffer()
            sink = sslSocket.sink().buffer()
            success=true

            sslSocket.soTimeout = 10_000
            val sink = sink!!
            val source = source!!
            source.timeout().timeout(10_000L, TimeUnit.MILLISECONDS)
            sink.timeout().timeout(10_000L, TimeUnit.MILLISECONDS)

            //step5:开始写请求头
            val requestLine = RequestLine.get(request)
            sink.writeUtf8(requestLine).writeUtf8("\r\n")
            for (i in 0 until request.headers.size) {
                sink.writeUtf8(request.headers.name(i))
                    .writeUtf8(": ")
                    .writeUtf8(request.headers.value(i))
                    .writeUtf8("\r\n")
            }
            sink.writeUtf8("\r\n")


            //step6: 开始写请求体
            //get请求和head请求不能有请求体
            if (HttpMethod.permitsRequestBody(request.method) && request.body != null) {
                //Expected:100-continue 请求头不支持
                //请求体默认不支持isDuplex
                var bufferRequestBody = request.body
                bufferRequestBody?.writeTo(sink)
                sink.close()
            } else {
                sink.flush()
            }

            //step7: 读取响应头
            val statusLine = StatusLine.parse(readHeaderLine(source))
            val response = Response.Builder()
                .code(statusLine.code)
                .request(request)
                .message(statusLine.message)
                .headers(readHeaders(source)).build()

            Log.e("okhttp", "response.code" + response.code)
            val contentType = response.header("Content-Type")
            val contentLength = response.headersContentLength()

           //这里看起来只是包装了source
            //step8: 处理响应体
            val rawSource = Http1ExchangeCodec(source).openResponseBodySource(response)
            val sourceWrapper = Http1ExchangeCodec.ResponseBodySource(rawSource, contentLength)
            val networkResponse = response.newBuilder()
                .body(RealResponseBody(contentType, contentLength, sourceWrapper.buffer()))
                .build()

            val responseBuilder = response.newBuilder()

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
                    responseBuilder.body(RealResponseBody(contentType, -1L, gzipSource.buffer()))
                }
            }
            return responseBuilder.build()
        } catch (e: IOException) {
            sslSocket?.closeQuietly()
            rawSocket?.close()
            rawSocket = null
            source = null
            sink = null
        } finally {
              if(!success){
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

    /** Reads headers or trailers. */
    private fun readHeaders(source: BufferedSource): Headers {
        val headers = Headers.Builder()
        // parse the result headers until the first blank line
        var line = readHeaderLine(source)
        while (line.isNotEmpty()) {
            headers.addLenient(line)
            line = readHeaderLine(source)
        }
        return headers.build()
    }

    companion object {
        fun newRealCall(client: OkHttpClient, originalRequest: Request): RealCall {
            // Safely publish the Call instance to the EventListener.
            return RealCall(client, originalRequest)
        }

    }
}