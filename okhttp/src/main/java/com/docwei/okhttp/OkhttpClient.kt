package com.docwei.okhttp

import immutableListOf
import java.security.KeyStore
import javax.net.SocketFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory


open class OkHttpClient internal constructor(builder: Builder) : Cloneable {
    constructor() : this(Builder())

    val dispatcher: Dispatcher = builder.dispatcher
    val socketFactory: SocketFactory = builder.socketFactory
    val sslSocketFactoryOrNull: SSLSocketFactory?
    val connectionSpec: List<ConnectionSpec> =builder.connectionSpec

    val sslSocketFactory: SSLSocketFactory
        get() = sslSocketFactoryOrNull ?: throw IllegalStateException("CLEARTEXT-only client")

    init {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        val trustManagers = factory.trustManagers!!
        //返回的是根证书
        val x509TrustManager = trustManagers[0]
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(x509TrustManager), null)
        sslSocketFactoryOrNull = sslContext.socketFactory
    }

    /** Prepares the [request] to be executed at some point in the future. */
     fun newCall(request: Request): RealCall1 {
        return RealCall1.newRealCall(this, request);
    }
    fun newCall2(request: Request): RealCall2 {
        return RealCall2.newRealCall(this, request);
    }

class Builder constructor() {
    internal var dispatcher: Dispatcher = Dispatcher();
    internal var socketFactory: SocketFactory = SocketFactory.getDefault()
    internal var sslSocketFactoryOrNull: SSLSocketFactory? = null
    internal var connectionSpec: List<ConnectionSpec> = DEFAULT_CONNECTION_SPEC;

    //todo拦截器 应用拦截器和网络拦截器
    //todo事件监听
    internal constructor(okHttpClient: OkHttpClient) : this() {
        this.dispatcher = okHttpClient.dispatcher;
        this.socketFactory = okHttpClient.socketFactory
        this.sslSocketFactoryOrNull = okHttpClient.sslSocketFactoryOrNull
        this.connectionSpec = okHttpClient.connectionSpec
    }
    fun build(): OkHttpClient = OkHttpClient(this)
}
    companion object{
        internal val DEFAULT_PROTOCOLS=immutableListOf(Protocol.HTTP_2,Protocol.HTTP_1_1)
        //排除明文传输的类型
        internal val DEFAULT_CONNECTION_SPEC=immutableListOf(ConnectionSpec.MODERN_TLS,ConnectionSpec.CLEARTEXT)

    }
}