package com.example.sampledebugapp.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Order(val id: String, val amount: Double, val customerType: String)

class OrderProcessor {
    fun calculateTotal(order: Order): Double {
        val discount = when (order.customerType) {
            "REGULAR" -> 0.05
            "GOLD" -> 0.15
            "PLATINUM" -> 0.25
            else -> throw IllegalArgumentException("Unknown customer type: ${order.customerType}")
        }
        val tax = order.amount * 0.10
        val finalPrice = (order.amount - (order.amount * discount)) + tax
        return finalPrice
    }
}

interface DataRepository {
    val data: Flow<List<String>>
    val lastOrderResult: Flow<String>
    fun processOrder(customerType: String, amount: Double)
}

class DefaultDataRepository : DataRepository {
    private val _data = MutableStateFlow(listOf("Order System Ready"))
    override val data: Flow<List<String>> = _data.asStateFlow()

    private val _lastOrderResult = MutableStateFlow("No order processed yet")
    override val lastOrderResult: Flow<String> = _lastOrderResult.asStateFlow()

    var totalOrdersProcessed: Int = 0

    private val processor = OrderProcessor()

    override fun processOrder(customerType: String, amount: Double) {
        totalOrdersProcessed++
        val orderId = "ORD-${System.currentTimeMillis() % 10000}"
        val order = Order(orderId, amount, customerType)
        val total = processor.calculateTotal(order)
        _lastOrderResult.value = "Order $orderId ($customerType): $$total"
    }
}
