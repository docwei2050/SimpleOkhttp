package com.docwei.okhttp

import com.android.okhttp.Handshake
import headersContentLength
import okhttp3.internal.http.StatusLine
import java.io.Closeable
import java.net.HttpURLConnection


class Response internal constructor(
    @get:JvmName("request") val request: Request,
    @get:JvmName("message") val message: String,
    @get:JvmName("code") val code: Int,
    @get:JvmName("handshake") val handshake: Handshake?,
    @get:JvmName("headers") val headers: Headers,
    @get:JvmName("body") val body: ResponseBody?
    ) : Closeable {

    //todo 缓存控制

    val isSuccessful: Boolean
        get() = code in 200..299

    fun headers(name: String): List<String> = headers.values(name)

    fun headers(name: String, defaultValue: String? = null): String? = headers[name] ?: defaultValue

    fun newBuilder(): Builder = Builder(this)

    val isRedirect: Boolean
        get() = when (code) {
            //304表示缓存可用啊，不是重定向
            //其余都跟重定向有关
            //300 多重选择  303see other
            307, 308, 300, 301, 302, 303 -> true
            else -> false
        }

    override fun close() {
        checkNotNull(body) { "response is not eligible for a body and must not be closed" }.close()

    }

    @JvmOverloads
    fun header(name: String, defaultValue: String? = null): String? = headers[name] ?: defaultValue


    override fun toString() =
        "Response{protocol= code=$code, message=$message, url=${request.url}}"





    open class Builder {
        internal var request: Request? = null;
        internal var code = -1;
        internal var message: String? = null
        internal var handshake: Handshake? = null
        internal var headers: Headers.Builder
        internal var body: ResponseBody? = null


        constructor() {
            headers = Headers.Builder()
        }

        //相当于在已有的response上创建builder
        internal constructor(response: Response) {
            this.request = response.request
            this.code = response.code
            this.message = response.message
            this.handshake = response.handshake
            this.headers=response.headers.newBuilder()
            this.body = response.body
        }


        open fun request(request: Request) = apply {
            this.request = request
        }

        open fun code(code: Int) = apply {
            this.code = code
        }

        open fun message(message: String) = apply {
            this.message = message
        }

        open fun handshake(handshake: Handshake?) = apply {
            this.handshake = handshake
        }

        open fun header(name: String, value: String) = apply {
            headers[name] = value
        }

        open fun addHeader(name: String, value: String) = apply {
            headers.add(name, value)
        }

        open fun removeHeader(name: String) = apply {
            headers.removeAll(name)
        }

        open fun headers(headers: Headers) = apply {
            this.headers = headers.newBuilder()
        }

        open fun body(body: ResponseBody?) = apply {
            this.body = body
        }



        private fun checkSupportResponse(name: String, response: Response?) {
            response?.apply {
                require(body == null) { "$name.body != null" }
            }
        }



        private fun checkPriorResponse(response: Response?) {
            response?.apply {
                require(body == null) { "priorResponse.body != null" }
            }
        }



        open fun build(): Response {
            return Response(
                checkNotNull(request){"request==null"},
                checkNotNull(message){"message==null"},
                code,
                handshake,
                headers.build(),
                body
            )
        }

    }
}
fun Response.promisesBody(): Boolean {
    // HEAD requests never yield a body regardless of the response headers.
    if (request.method == "HEAD") {
        return false
    }

    val responseCode = code
    if ((responseCode < StatusLine.HTTP_CONTINUE || responseCode >= 200) &&
        responseCode != HttpURLConnection.HTTP_NO_CONTENT &&
        responseCode != HttpURLConnection.HTTP_NOT_MODIFIED
    ) {
        return true
    }

    // If the Content-Length or Transfer-Encoding headers disagree with the response code, the
    // response is malformed. For best compatibility, we honor the headers.
    if (headersContentLength() != -1L ||
        "chunked".equals(header("Transfer-Encoding"), ignoreCase = true)) {
        return true
    }

    return false
}