package com.example.sampledebugapp.data

import com.example.sampledebugapp.domain.CartItem
import com.example.sampledebugapp.domain.MembershipTier
import com.example.sampledebugapp.domain.PromoCoupon
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface CartRepository {
    val currentTier: StateFlow<MembershipTier>
    val availableCoupons: List<PromoCoupon>
    fun getInitialCart(): List<CartItem>
}

object DefaultCartRepository : CartRepository {

    private val initialItems = listOf(
        CartItem(
            id = "item_headphones",
            title = "Studio Wireless Pro Headphones",
            unitPrice = 299.99,
            quantity = 1,
            category = "Audio",
        ),
        CartItem(
            id = "item_smartwatch",
            title = "Apex Smart Fitness Watch",
            unitPrice = 199.50,
            quantity = 1,
            category = "Wearables",
        ),
        CartItem(
            id = "item_keyboard",
            title = "MechPro Wireless Keyboard",
            unitPrice = 129.00,
            quantity = 1,
            category = "Accessories",
        ),
    )

    private val _currentTier = MutableStateFlow(MembershipTier.GOLD)
    override val currentTier: StateFlow<MembershipTier> = _currentTier.asStateFlow()

    override val availableCoupons: List<PromoCoupon> = listOf(
        PromoCoupon(code = "TECH15", discountPercent = 15.0, minSpend = 600.0),
        PromoCoupon(code = "AUDIO20", discountPercent = 20.0, minSpend = 200.0, applicableCategory = "Audio"),
    )

    override fun getInitialCart(): List<CartItem> = initialItems
}
