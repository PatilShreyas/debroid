package com.example.sampledebugapp.domain

import kotlinx.serialization.Serializable

@Serializable
data class CartItem(
    val id: String,
    val title: String,
    val unitPrice: Double,
    val quantity: Int,
    val category: String,
)

@Serializable
enum class MembershipTier(val discountPercentage: Double) {
    STANDARD(0.0),
    SILVER(0.05),
    GOLD(0.10),
    PLATINUM(0.20),
}

@Serializable
data class PromoCoupon(
    val code: String,
    val discountPercent: Double,
    val minSpend: Double,
    val applicableCategory: String? = null,
)

@Serializable
data class OrderCalculation(
    val grossSubtotal: Double,
    val memberDiscount: Double,
    val promoDiscount: Double,
    val taxableAmount: Double,
    val taxAmount: Double,
    val shippingFee: Double,
    val finalTotal: Double,
)
