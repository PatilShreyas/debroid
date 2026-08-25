package com.example.sampledebugapp.ui.cart

import com.example.sampledebugapp.data.CartRepository
import com.example.sampledebugapp.domain.CartItem
import com.example.sampledebugapp.domain.MembershipTier
import com.example.sampledebugapp.domain.PromoCoupon
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CartViewModelTest {

    private val fakeItems = listOf(
        CartItem("item_1", "Pro Headphones", 100.0, 1, "Audio"),
        CartItem("item_2", "Smart Watch", 50.0, 2, "Wearables"),
    )

    @Test
    fun loadInitialState_populatesCartAndCalculates() = runTest {
        val repo = FakeCartRepository(fakeItems)
        val viewModel = CartViewModel(repo)

        val state = viewModel.uiState.first()
        assertEquals(2, state.items.size)
        assertNotNull(state.calculation)
        assertEquals(200.0, state.calculation.grossSubtotal, 0.01)
    }

    @Test
    fun applyCoupon_validCoupon_appliesDiscount() = runTest {
        val repo = FakeCartRepository(fakeItems)
        val viewModel = CartViewModel(repo)

        viewModel.applyCoupon("TEST10")

        val state = viewModel.uiState.first()
        assertEquals("TEST10", state.appliedCoupon?.code)
        assertNotNull(state.couponMessage)
        assertTrue(state.couponMessage!!.contains("successfully"))
    }

    @Test
    fun applyCoupon_invalidCoupon_setsErrorMessage() = runTest {
        val repo = FakeCartRepository(fakeItems)
        val viewModel = CartViewModel(repo)

        viewModel.applyCoupon("INVALID_CODE")

        val state = viewModel.uiState.first()
        assertNull(state.appliedCoupon)
        assertTrue(state.couponMessage!!.contains("invalid"))
    }

    @Test
    fun applyCoupon_unmetMinSpend_doesNotApplyCoupon() = runTest {
        val repo = FakeCartRepository(fakeItems)
        val viewModel = CartViewModel(repo)

        viewModel.applyCoupon("BIGSPEND")

        val state = viewModel.uiState.first()
        assertNull(state.appliedCoupon)
        assertTrue(state.couponMessage!!.contains("requires min spend"))
    }

    @Test
    fun setExpressShipping_updatesShippingFee() = runTest {
        val repo = FakeCartRepository(fakeItems)
        val viewModel = CartViewModel(repo)

        viewModel.setExpressShipping(false)

        val state = viewModel.uiState.first()
        assertEquals(false, state.expressShipping)
        assertEquals(10.0, state.calculation.shippingFee, 0.01)
    }

    @Test
    fun applyCoupon_qualifiesBasedOnGrossSubtotalEvenWhenNetSubtotalIsBelowThreshold() = runTest {
        val itemsOver600 = listOf(
            CartItem("item_headphones", "Studio Wireless Pro Headphones", 299.99, 1, "Audio"),
            CartItem("item_smartwatch", "Apex Smart Fitness Watch", 199.50, 1, "Wearables"),
            CartItem("item_keyboard", "MechPro Wireless Keyboard", 129.00, 1, "Accessories"),
        )
        val repo = object : CartRepository {
            override val currentTier: StateFlow<MembershipTier> = MutableStateFlow(MembershipTier.GOLD).asStateFlow()
            override val availableCoupons: List<PromoCoupon> = listOf(
                PromoCoupon(code = "TECH15", discountPercent = 15.0, minSpend = 600.0),
            )
            override fun getInitialCart(): List<CartItem> = itemsOver600
        }
        val viewModel = CartViewModel(repo)

        viewModel.applyCoupon("TECH15")

        val state = viewModel.uiState.first()
        assertEquals(628.49, state.calculation.grossSubtotal, 0.01)
        assertEquals(62.85, state.calculation.memberDiscount, 0.01)
        assertEquals("TECH15", state.appliedCoupon?.code)
        assertEquals(94.27, state.calculation.promoDiscount, 0.01)
        assertEquals(0.0, state.calculation.shippingFee, 0.01) // Express shipping is free over $600 gross subtotal
        assertTrue(state.couponMessage!!.contains("successfully"))
    }
}

private class FakeCartRepository(
    private val initial: List<CartItem>,
) : CartRepository {
    private val _currentTier = MutableStateFlow(MembershipTier.GOLD)
    override val currentTier: StateFlow<MembershipTier> = _currentTier.asStateFlow()

    override val availableCoupons: List<PromoCoupon> = listOf(
        PromoCoupon("TEST10", 10.0, 50.0),
        PromoCoupon("BIGSPEND", 20.0, 500.0),
    )

    override fun getInitialCart(): List<CartItem> = initial
}
