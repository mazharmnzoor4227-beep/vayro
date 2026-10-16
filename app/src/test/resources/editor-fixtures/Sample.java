package com.example.fixtures;

/**
 * Fixture: Java sample for editor regression tests (Milestone 2).
 */
public class SampleJava {
    private double lastResult = 0.0;

    public double add(double a, double b) {
        lastResult = a + b;
        return lastResult;
    }

    public void reset() {
        lastResult = 0.0;
    }

    public static void main(String[] args) {
        SampleJava calc = new SampleJava();
        double sum = calc.add(2.0, 3.0);
        System.out.println("sum=" + sum);
        calc.reset();
    }
}
