package com.example.fixtures

/**
 * Fixture: Kotlin sample for editor regression tests (Milestone 2).
 * Covers class, functions, properties, and call sites used by the
 * heuristic intelligence provider and rename engine tests.
 */
class Calculator {
    private var lastResult: Double = 0.0

    fun add(a: Double, b: Double): Double {
        lastResult = a + b
        return lastResult
    }

    fun multiply(a: Double, b: Double): Double {
        lastResult = a * b
        return lastResult
    }

    fun reset() {
        lastResult = 0.0
    }
}

fun main() {
    val calculator = Calculator()
    val sum = calculator.add(2.0, 3.0)
    val product = calculator.multiply(sum, 4.0)
    println("sum=$sum product=$product")
    calculator.reset()
}
