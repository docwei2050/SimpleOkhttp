package com.docwei.okhttp

import format
import java.util.*
import kotlin.collections.ArrayList


class Headers private constructor(
    private val namesAndValues
    : Array<String>
) : Iterable<Pair<String, String>> {
    operator fun get(name: String): String? = get(namesAndValues, name)
    fun name(index: Int): String = namesAndValues[index * 2]
    fun value(index: Int): String = namesAndValues[index * 2 + 1]

    fun byteCount(): Long {
        // Each header name has 2 bytes of overhead for ': ' and every header value has 2 bytes of
        // overhead for '\r\n'.
        var result = (namesAndValues.size * 2).toLong()

        for (i in 0 until namesAndValues.size) {
            result += namesAndValues[i].length.toLong()
        }
        return result
    }


    fun newBuilder(): Builder {
        val result = Builder()
        result.namesAndValues += namesAndValues
        return result
    }

    @get:JvmName("size")
    val size: Int
        get() = namesAndValues.size / 2

    override fun equals(other: Any?): Boolean {
        return other is Headers && namesAndValues.contentEquals(other.namesAndValues)
    }



  fun values(name: String): List<String> {
    var result: MutableList<String>? = null
    for (i in 0 until size) {
      if (name.equals(name(i), ignoreCase = true)) {
        if (result == null) result = java.util.ArrayList(2)
        result.add(value(i))
      }
    }
    return if (result != null) {
      Collections.unmodifiableList(result)
    } else {
      emptyList()
    }
  }

    override fun hashCode(): Int = namesAndValues.contentHashCode()

    override fun toString(): String {
        return buildString {
            for (i in 0 until size) {
                append(name(i))
                append(": ")
                append(value(i))
                append("\n")
            }
        }
    }

    fun toMultimap(): Map<String, List<String>> {
        val result = TreeMap<String, MutableList<String>>(String.CASE_INSENSITIVE_ORDER)
        for (i in 0 until size) {
            val name = name(i).toLowerCase(Locale.US)
            var values: MutableList<String>? = result[name]
            if (values == null) {
                values = ArrayList(2)
                result[name] = values
            }
            values.add(value(i))
        }
        return result
    }

    open class Builder {
        internal val namesAndValues: MutableList<String> = ArrayList(20)
        internal fun addLenient(line: String) = apply {
            val index = line.indexOf(':', 1)
            when {
                index != -1 -> {
                    addLenient(line.substring(0, index), line.substring(index + 1))
                }
                line[0] == ':' -> {
                    addLenient("", line.substring(1)) // Empty header name.
                }
                else -> {
                    addLenient("", line)
                }
            }
        }


        fun add(name: String, value: String) = apply {
            checkName(name)
            checkValue(value, name)
            addLenient(name, value)
        }

        fun addUnsafeNonAscii(name: String, value: String) = apply {
            checkName(name)
            addLenient(name, value)
        }

        fun addAll(headers: Headers) = apply {
            for (i in 0 until headers.size) {
                addLenient(headers.name(i), headers.value(i))
            }
        }

        internal fun addLenient(name: String, value: String) = apply {
            namesAndValues.add(name)
            namesAndValues.add(value.trim())
        }

        fun removeAll(name: String) = apply {
            var i = 0
            while (i < namesAndValues.size) {
                if (name.equals(namesAndValues[i], ignoreCase = true)) {
                    namesAndValues.removeAt(i) // name
                    namesAndValues.removeAt(i) // value
                    i -= 2
                }
                i += 2
            }
        }

        operator fun set(name: String, value: String) = apply {
            checkName(name)
            checkValue(value, name)
            removeAll(name)
            addLenient(name, value)
        }

        operator fun get(name: String): String? {
            for (i in namesAndValues.size - 2 downTo 0 step 2) {
                if (name.equals(namesAndValues[i], ignoreCase = true)) {
                    return namesAndValues[i + 1]
                }
            }
            return null
        }

        fun build(): Headers = Headers(namesAndValues.toTypedArray())
    }

    companion object {
        private fun get(namesAndValues: Array<String>, name: String): String? {
            for (i in namesAndValues.size - 2 downTo 0 step 2) {
                if (name.equals(namesAndValues[i], ignoreCase = true)) {
                    return namesAndValues[i + 1]
                }
            }
            return null
        }

        @JvmStatic
        @JvmName("of")
        fun headersOf(vararg namesAndValues: String): Headers {
            require(namesAndValues.size % 2 == 0) { "Expected alternating header names and values" }
            // Make a defensive copy and clean it up.
            val namesAndValues: Array<String> = namesAndValues.clone() as Array<String>
            for (i in namesAndValues.indices) {
                require(namesAndValues[i] != null) { "Headers cannot be null" }
                namesAndValues[i] = namesAndValues[i].trim()
            }

            // Check for malformed headers.
            for (i in 0 until namesAndValues.size step 2) {
                val name = namesAndValues[i]
                val value = namesAndValues[i + 1]
                checkName(name)
                checkValue(value, name)
            }

            return Headers(namesAndValues)
        }

        @JvmStatic
        @JvmName("of")
        fun Map<String, String>.toHeaders(): Headers {
            // Make a defensive copy and clean it up.
            val namesAndValues = arrayOfNulls<String>(size * 2)
            var i = 0
            for ((k, v) in this) {
                val name = k.trim()
                val value = v.trim()
                checkName(name)
                checkValue(value, name)
                namesAndValues[i] = name
                namesAndValues[i + 1] = value
                i += 2
            }

            return Headers(namesAndValues as Array<String>)
        }

        private fun checkName(name: String) {
            require(name.isNotEmpty()) { "name is empty" }
            for (i in 0 until name.length) {
                val c = name[i]
                require(c in '\u0021'..'\u007e') {
                    format("Unexpected char %#04x at %d in header name: %s", c.toInt(), i, name)
                }
            }
        }

        private fun checkValue(value: String, name: String) {
            for (i in 0 until value.length) {
                val c = value[i]
                require(c == '\t' || c in '\u0020'..'\u007e') {
                    format("Unexpected char %#04x at %d in %s value: %s", c.toInt(), i, name, value)
                }
            }
        }
    }

    override fun iterator(): Iterator<Pair<String, String>> {
        return Array(size) {
            name(it) to value(it)
        }.iterator()
    }
}
