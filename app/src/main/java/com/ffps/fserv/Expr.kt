package com.ffps.fserv

/**
 * Условия в конфиге задач: "moved", "not moved", "moved and bp", "bp > 140 or pulse > 100", "(a or b) and not c".
 * Значения: true/false, числа, строки в кавычках и переменные задачи. Без зависимостей от Android.
 */
object Expr {
    sealed class Node {
        abstract fun eval(vars: Map<String, Any?>): Any?
        abstract fun idents(out: MutableSet<String>)
    }

    private class Lit(val v: Any?) : Node() {
        override fun eval(vars: Map<String, Any?>) = v
        override fun idents(out: MutableSet<String>) {}
    }

    private class Id(val name: String) : Node() {
        override fun eval(vars: Map<String, Any?>) = vars[name]
        override fun idents(out: MutableSet<String>) { out.add(name) }
    }

    private class Not(val a: Node) : Node() {
        override fun eval(vars: Map<String, Any?>) = !truthy(a.eval(vars))
        override fun idents(out: MutableSet<String>) = a.idents(out)
    }

    private class Bin(val op: String, val a: Node, val b: Node) : Node() {
        override fun eval(vars: Map<String, Any?>): Any? {
            when (op) {
                "and" -> return truthy(a.eval(vars)) && truthy(b.eval(vars))
                "or" -> return truthy(a.eval(vars)) || truthy(b.eval(vars))
            }
            val x = a.eval(vars)
            val y = b.eval(vars)
            if (op == "==" || op == "!=") {
                val eq = if (x is Number && y is Number) x.toDouble() == y.toDouble() else x == y
                return if (op == "==") eq else !eq
            }
            // сравнения с отсутствующим значением ложны (например, давление ещё не измерено)
            val dx = (x as? Number)?.toDouble() ?: return false
            val dy = (y as? Number)?.toDouble() ?: return false
            return when (op) {
                "<" -> dx < dy
                ">" -> dx > dy
                "<=" -> dx <= dy
                else -> dx >= dy
            }
        }

        override fun idents(out: MutableSet<String>) { a.idents(out); b.idents(out) }
    }

    fun truthy(v: Any?): Boolean = when (v) {
        null -> false
        is Boolean -> v
        is Number -> v.toDouble() != 0.0
        is String -> v.isNotEmpty()
        else -> true
    }

    private class P(val toks: List<String>) {
        var i = 0
        fun peek(): String? = toks.getOrNull(i)
        fun next(): String = toks[i++]

        fun or(): Node {
            var l = and()
            while (peek() == "or") { next(); l = Bin("or", l, and()) }
            return l
        }

        fun and(): Node {
            var l = not()
            while (peek() == "and") { next(); l = Bin("and", l, not()) }
            return l
        }

        fun not(): Node {
            if (peek() == "not") { next(); return Not(not()) }
            return cmp()
        }

        fun cmp(): Node {
            val l = atom()
            val op = peek()
            if (op in listOf("==", "!=", "<", ">", "<=", ">=")) {
                next()
                return Bin(op!!, l, atom())
            }
            return l
        }

        fun atom(): Node {
            val t = peekOrFail()
            next()
            if (t == "(") {
                val e = or()
                if (peek() != ")") throw IllegalArgumentException("нет закрывающей скобки")
                next()
                return e
            }
            if (t == "true") return Lit(true)
            if (t == "false") return Lit(false)
            if (t.startsWith("\"") || t.startsWith("'")) return Lit(t.substring(1, t.length - 1))
            t.toDoubleOrNull()?.let { return Lit(if (it == Math.floor(it) && Math.abs(it) < 1e9) it.toLong() else it) }
            if (Regex("[A-Za-z_][A-Za-z0-9_]*").matches(t) && t !in listOf("and", "or", "not")) return Id(t)
            throw IllegalArgumentException("неожиданное «$t»")
        }

        fun peekOrFail(): String = peek() ?: throw IllegalArgumentException("условие оборвано")
    }

    private fun tokenize(s: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.isWhitespace() -> i++
                c == '(' || c == ')' -> { out.add(c.toString()); i++ }
                c == '"' || c == '\'' -> {
                    val j = s.indexOf(c, i + 1)
                    if (j < 0) throw IllegalArgumentException("нет закрывающей кавычки")
                    out.add(s.substring(i, j + 1)); i = j + 1
                }
                c == '=' || c == '!' || c == '<' || c == '>' -> {
                    val two = if (i + 1 < s.length) s.substring(i, i + 2) else ""
                    if (two in listOf("==", "!=", "<=", ">=")) { out.add(two); i += 2 }
                    else if (c == '<' || c == '>') { out.add(c.toString()); i++ }
                    else throw IllegalArgumentException("неверный знак «$c» (для сравнения на равенство используйте ==)")
                }
                c.isLetterOrDigit() || c == '_' || c == '.' || c == '-' -> {
                    var j = i + 1
                    while (j < s.length && (s[j].isLetterOrDigit() || s[j] == '_' || s[j] == '.')) j++
                    out.add(s.substring(i, j)); i = j
                }
                else -> throw IllegalArgumentException("неожиданный символ «$c»")
            }
        }
        return out
    }

    /** Разбор условия; ошибка описывается по-русски. */
    fun parse(text: String): Node {
        val t = text.trim()
        if (t.isEmpty()) throw IllegalArgumentException("пустое условие")
        val p = P(tokenize(t))
        val n = p.or()
        if (p.peek() != null) throw IllegalArgumentException("лишнее «${p.peek()}»")
        return n
    }

    fun eval(text: String, vars: Map<String, Any?>): Boolean = truthy(parse(text).eval(vars))

    fun idents(text: String): Set<String> = LinkedHashSet<String>().also { parse(text).idents(it) }
}
