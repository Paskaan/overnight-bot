package com.pascalh.overnightbot.core

/** Small JSON reader/writer. Objects become Map, arrays List, numbers Double. */
object MiniJson {
    fun parse(text: String): Any? {
        val p = Parser(text)
        p.ws()
        val v = p.value()
        p.ws()
        if (p.i != text.length) throw IllegalArgumentException("Unexpected JSON content at ${p.i}")
        return v
    }

    fun stringify(v: Any?): String {
        val sb = StringBuilder()
        write(v, sb)
        return sb.toString()
    }

    private fun write(v: Any?, sb: StringBuilder) {
        when (v) {
            null -> sb.append("null")
            is Boolean -> sb.append(v.toString())
            is Int, is Long -> sb.append(v.toString())
            is Number -> {
                val d = v.toDouble()
                if (d.isNaN() || d.isInfinite()) sb.append("0")
                else if (d == Math.rint(d) && Math.abs(d) < 1e15) sb.append(d.toLong().toString())
                else sb.append(d.toString())
            }
            is String -> quote(v, sb)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    quote(k.toString(), sb)
                    sb.append(':')
                    write(value, sb)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (item in v) {
                    if (!first) sb.append(',')
                    first = false
                    write(item, sb)
                }
                sb.append(']')
            }
            else -> quote(v.toString(), sb)
        }
    }

    private fun quote(s: String, sb: StringBuilder) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append(String.format("\\u%04x", c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var i = 0

        fun ws() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            if (i >= s.length) throw IllegalArgumentException("Unexpected end of JSON")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw IllegalArgumentException("Unexpected '$c' at $i")
            }
        }

        private fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) throw IllegalArgumentException("Bad literal at $i")
            i += word.length
            return v
        }

        private fun num(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(start, i).toDouble()
        }

        private fun str(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw IllegalArgumentException("Unterminated string")
                val c = s[i++]
                if (c == '"') break
                if (c != '\\') {
                    sb.append(c)
                    continue
                }
                if (i >= s.length) throw IllegalArgumentException("Bad escape")
                when (val e = s[i++]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'u' -> {
                        sb.append(s.substring(i, i + 4).toInt(16).toChar())
                        i += 4
                    }
                    else -> sb.append(e)
                }
            }
            return sb.toString()
        }

        private fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++
            ws()
            if (i < s.length && s[i] == '}') {
                i++
                return m
            }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') throw IllegalArgumentException("Expected key at $i")
                val k = str()
                ws()
                if (i >= s.length || s[i] != ':') throw IllegalArgumentException("Expected ':' at $i")
                i++
                ws()
                m[k] = value()
                ws()
                if (i >= s.length) throw IllegalArgumentException("Unterminated object")
                val c = s[i++]
                if (c == '}') break
                if (c != ',') throw IllegalArgumentException("Expected ',' at ${i - 1}")
            }
            return m
        }

        private fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++
            ws()
            if (i < s.length && s[i] == ']') {
                i++
                return l
            }
            while (true) {
                ws()
                l.add(value())
                ws()
                if (i >= s.length) throw IllegalArgumentException("Unterminated array")
                val c = s[i++]
                if (c == ']') break
                if (c != ',') throw IllegalArgumentException("Expected ',' at ${i - 1}")
            }
            return l
        }
    }
}

typealias JObj = Map<String, Any?>

@Suppress("UNCHECKED_CAST")
fun Any?.asObj(): JObj = (this as? Map<String, Any?>) ?: emptyMap()

@Suppress("UNCHECKED_CAST")
fun Any?.asArr(): List<Any?> = (this as? List<Any?>) ?: emptyList()

fun JObj.s(key: String): String = when (val v = this[key]) {
    null -> ""
    is String -> v
    is Number -> MiniJson.stringify(v)
    else -> v.toString()
}

fun JObj.d(key: String): Double = when (val v = this[key]) {
    is Number -> v.toDouble()
    is String -> v.toDoubleOrNull() ?: 0.0
    else -> 0.0
}

fun JObj.b(key: String): Boolean = when (val v = this[key]) {
    is Boolean -> v
    is String -> v == "true"
    else -> false
}

fun JObj.o(key: String): JObj = this[key].asObj()

fun JObj.a(key: String): List<Any?> = this[key].asArr()
