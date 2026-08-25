package com.example.sampledebugapp.domain

class OrderPricingPipeline(
    private val tierDiscountEngine: TierDiscountEngine = TierDiscountEngine(),
    private val promoCouponEngine: PromoCouponEngine = PromoCouponEngine(),
    private val taxCalculationService: TaxCalculationService = TaxCalculationService(),
    private val shippingRateProvider: ShippingRateProvider = ShippingRateProvider(),
) {
    fun calculateOrder(
        items: List<CartItem>,
        tier: MembershipTier,
        coupon: PromoCoupon?,
        expressShipping: Boolean,
    ): OrderCalculation {
        val grossSubtotal = Math.round(items.sumOf { it.unitPrice * it.quantity } * 100.0) / 100.0
        val memberDiscount = tierDiscountEngine.computeDiscount(tier, grossSubtotal)
        val netSubtotal = grossSubtotal - memberDiscount

        val promoDiscount = promoCouponEngine.evaluatePromo(coupon, grossSubtotal, items)
        val taxableAmount = Math.max(0.0, netSubtotal - promoDiscount)
        val taxAmount = taxCalculationService.computeTax(taxableAmount)
        val shippingFee = shippingRateProvider.calculateShipping(grossSubtotal, expressShipping)

        val finalTotal = Math.round((taxableAmount + taxAmount + shippingFee) * 100.0) / 100.0

        return OrderCalculation(
            grossSubtotal = grossSubtotal,
            memberDiscount = memberDiscount,
            promoDiscount = promoDiscount,
            taxableAmount = taxableAmount,
            taxAmount = taxAmount,
            shippingFee = shippingFee,
            finalTotal = finalTotal,
        )
    }
}
