package com.example.sampledebugapp.domain

class PromoCouponEngine {
    fun evaluatePromo(coupon: PromoCoupon?, baseAmount: Double, items: List<CartItem>): Double {
        if (coupon == null) return 0.0

        val eligibleSpend = if (coupon.applicableCategory != null) {
            items.filter { it.category == coupon.applicableCategory }.sumOf { it.unitPrice * it.quantity }
        } else {
            baseAmount
        }

        if (eligibleSpend < coupon.minSpend) {
            return 0.0
        }

        val discount = eligibleSpend * (coupon.discountPercent / 100.0)
        return Math.round(discount * 100.0) / 100.0
    }
}
