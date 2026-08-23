package com.example.sampledebugapp.ui.cart

import androidx.lifecycle.ViewModel
import com.example.sampledebugapp.data.CartRepository
import com.example.sampledebugapp.data.DefaultCartRepository
import com.example.sampledebugapp.domain.CartItem
import com.example.sampledebugapp.domain.MembershipTier
import com.example.sampledebugapp.domain.OrderCalculation
import com.example.sampledebugapp.domain.OrderPricingPipeline
import com.example.sampledebugapp.domain.PromoCoupon
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CartUiState(
    val items: List<CartItem> = emptyList(),
    val tier: MembershipTier = MembershipTier.GOLD,
    val couponInput: String = "",
    val appliedCoupon: PromoCoupon? = null,
    val expressShipping: Boolean = true,
    val calculation: OrderCalculation = OrderCalculation(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
    val couponMessage: String? = null,
)

class CartViewModel(
    private val repository: CartRepository = DefaultCartRepository,
    private val pipeline: OrderPricingPipeline = OrderPricingPipeline(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(CartUiState())
    val uiState: StateFlow<CartUiState> = _uiState.asStateFlow()

    init {
        loadInitialState()
    }

    fun loadInitialState() {
        val items = repository.getInitialCart()
        val tier = repository.currentTier.value
        val express = true

        val calculation = pipeline.calculateOrder(
            items = items,
            tier = tier,
            coupon = null,
            expressShipping = express,
        )

        _uiState.value = CartUiState(
            items = items,
            tier = tier,
            couponInput = "TECH15",
            appliedCoupon = null,
            expressShipping = express,
            calculation = calculation,
        )
    }

    fun updateCouponInput(input: String) {
        _uiState.value = _uiState.value.copy(couponInput = input)
    }

    fun applyCoupon(code: String) {
        val current = _uiState.value
        val trimmed = code.trim()
        val coupon = repository.availableCoupons.find { it.code.equals(trimmed, ignoreCase = true) }

        val calculation = pipeline.calculateOrder(
            items = current.items,
            tier = current.tier,
            coupon = coupon,
            expressShipping = current.expressShipping,
        )

        val isSuccess = coupon != null && calculation.promoDiscount > 0.0
        val message = when {
            coupon == null -> "Coupon code '$trimmed' is invalid."
            !isSuccess -> "Coupon '$trimmed' requires min spend of $${coupon.minSpend}."
            else -> "Coupon '$trimmed' applied successfully!"
        }

        _uiState.value = current.copy(
            appliedCoupon = if (isSuccess) coupon else null,
            calculation = calculation,
            couponMessage = message,
        )
    }

    fun setExpressShipping(enabled: Boolean) {
        val current = _uiState.value
        val calculation = pipeline.calculateOrder(
            items = current.items,
            tier = current.tier,
            coupon = current.appliedCoupon,
            expressShipping = enabled,
        )
        _uiState.value = current.copy(
            expressShipping = enabled,
            calculation = calculation,
        )
    }
}
