package com.example.sampledebugapp.domain

class TaxCalculationService {
    fun computeTax(taxableAmount: Double, taxRate: Double = 0.085): Double {
        val tax = taxableAmount * taxRate
        return Math.round(tax * 100.0) / 100.0
    }
}
