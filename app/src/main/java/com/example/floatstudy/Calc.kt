package com.example.floatstudy

import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.E
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

object Calc {
    fun eval(input: String): Double {
        val clean = input
            .replace(" ", "")
            .replace("×", "*")
            .replace("÷", "/")
            .replace("√", "sqrt")
            .replace("π", "pi")
            .replace(",", ".")
        return Parser(clean).parse()
    }

    fun format(v: Double): String {
        if (v.isNaN() || v.isInfinite()) return "Error"
        if (v == floor(v) && abs(v) < 1e15) return v.toLong().toString()
        return BigDecimal(v).round(MathContext(12)).stripTrailingZeros().toPlainString()
    }

    private class Parser(val s: String) {
        var i = 0

        fun peek(): Char = if (i < s.length) s[i] else '\u0000'

        fun parse(): Double {
            val v = expr()
            if (i < s.length) throw IllegalArgumentException("Karakter tidak valid")
            return v
        }

        fun expr(): Double {
            var v = term()
            while (peek() == '+' || peek() == '-') {
                val op = s[i++]
                val r = term()
                v = if (op == '+') v + r else v - r
            }
            return v
        }

        fun term(): Double {
            var v = unary()
            while (peek() == '*' || peek() == '/') {
                val op = s[i++]
                val r = unary()
                v = if (op == '*') v * r else v / r
            }
            return v
        }

        fun unary(): Double {
            if (peek() == '-') {
                i++
                return -unary()
            }
            if (peek() == '+') {
                i++
                return unary()
            }
            return power()
        }

        fun power(): Double {
            val b = postfix()
            if (peek() == '^') {
                i++
                return b.pow(unary())
            }
            return b
        }

        fun postfix(): Double {
            var v = primary()
            while (peek() == '%') {
                i++
                v /= 100.0
            }
            return v
        }

        fun primary(): Double {
            val ch = peek()
            if (ch == '(') {
                i++
                val v = expr()
                if (peek() != ')') throw IllegalArgumentException("Kurung tidak lengkap")
                i++
                return v
            }
            if (ch.isDigit() || ch == '.') {
                val st = i
                while (peek().isDigit() || peek() == '.') i++
                return s.substring(st, i).toDouble()
            }
            if (ch.isLetter()) {
                val st = i
                while (peek().isLetter()) i++
                val name = s.substring(st, i)
                if (name == "pi") return PI
                if (name == "e") return E
                if (peek() != '(') throw IllegalArgumentException("Fungsi butuh kurung")
                i++
                val a = expr()
                if (peek() != ')') throw IllegalArgumentException("Kurung tidak lengkap")
                i++
                return when (name) {
                    "sqrt" -> sqrt(a)
                    "sin" -> sin(Math.toRadians(a))
                    "cos" -> cos(Math.toRadians(a))
                    "tan" -> tan(Math.toRadians(a))
                    "ln" -> ln(a)
                    "log" -> log10(a)
                    else -> throw IllegalArgumentException("Fungsi tidak dikenal")
                }
            }
            throw IllegalArgumentException("Ekspresi tidak valid")
        }
    }
}
