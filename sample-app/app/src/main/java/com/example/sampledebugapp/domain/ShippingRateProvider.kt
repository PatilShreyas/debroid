package com.example.sampledebugapp.domain

class ShippingRateProvider {
    fun calculateShipping(subtotal: Double, express: Boolean): Double {
        if (!express && subtotal >= 500.0) {
            return 0.0
        }
        val baseFee = if (express) 25.0 else 10.0
        return if (subtotal >= 600.0 && express) 0.0 else baseFee
    }
}
