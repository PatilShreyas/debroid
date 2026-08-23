package com.example.sampledebugapp.domain

class TierDiscountEngine {
    fun computeDiscount(tier: MembershipTier, grossAmount: Double): Double {
        val discount = grossAmount * tier.discountPercentage
        return Math.round(discount * 100.0) / 100.0
    }
}
