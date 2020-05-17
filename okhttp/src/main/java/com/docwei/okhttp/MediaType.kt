package com.docwei.okhttp

import java.nio.charset.Charset
import java.util.*
import java.util.regex.Pattern

class MediaType private constructor(
    private val mediaType:String,
    @get:JvmName("type")
    val type:String,
    @get:JvmName("subType")
    val subType:String,
    private val charset:String?){
    override fun toString()=mediaType
    @JvmOverloads
    fun charset(defaultValue: Charset? = null): Charset? {
        return try {
            if (charset != null) Charset.forName(charset) else defaultValue
        } catch (_: IllegalArgumentException) {
            defaultValue // This charset is invalid or unsupported. Give up.
        }
    }
    companion object {
        private const val TOKEN = "([a-zA-Z0-9-!#$%&'*+.^_`{|}~]+)"
        private const val QUOTED = "\"([^\"]*)\""
        private val TYPE_SUBTYPE = Pattern.compile("$TOKEN/$TOKEN")
        private val PARAMETER = Pattern.compile(";\\s*(?:$TOKEN=(?:$TOKEN|$QUOTED))?")

        @JvmStatic
        @JvmName("get")
        fun String.toMediaType(): MediaType {
            val typeSubtype = TYPE_SUBTYPE.matcher(this)
            require(typeSubtype.lookingAt()) { "No subtype found for: \"$this\"" }
            val type = typeSubtype.group(1).toLowerCase(Locale.US)
            val subtype = typeSubtype.group(2).toLowerCase(Locale.US)

            var charset: String? = null
            val parameter = PARAMETER.matcher(this)
            var s = typeSubtype.end()
            while (s < length) {
                parameter.region(s, length)
                require(parameter.lookingAt()) {
                    "Parameter is not formatted correctly: \"${substring(s)}\" for: \"$this\""
                }

                val name = parameter.group(1)
                if (name == null || !name.equals("charset", ignoreCase = true)) {
                    s = parameter.end()
                    continue
                }
                val charsetParameter: String
                val token = parameter.group(2)
                charsetParameter = when {
                    token == null -> {
                        // Value is "double-quoted". That's valid and our regex group already strips the quotes.
                        parameter.group(3)
                    }
                    token.startsWith("'") && token.endsWith("'") && token.length > 2 -> {
                        // If the token is 'single-quoted' it's invalid! But we're lenient and strip the quotes.
                        token.substring(1, token.length - 1)
                    }
                    else -> token
                }
                require(charset == null || charsetParameter.equals(charset, ignoreCase = true)) {
                    "Multiple charsets defined: \"$charset\" and: \"$charsetParameter\" for: \"$this\""
                }
                charset = charsetParameter
                s = parameter.end()
            }

            return MediaType(this, type, subtype, charset)
        }

        @JvmStatic
        @JvmName("parse")
        fun String.toMediaTypeOrNull(): MediaType? {
            return try {
                toMediaType()
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }


}