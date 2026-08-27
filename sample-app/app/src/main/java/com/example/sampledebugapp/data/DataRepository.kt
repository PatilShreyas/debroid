package com.example.sampledebugapp.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Order(val id: String, val amount: Double, val customerType: String)
data class Address(val city: String, val zipCode: String)
data class User(val name: String, val address: Address?)
data class ComplexOrder(
    val id: String,
    val amount: Double,
    val customerType: String,
    val isExpress: Boolean,
    val user: User?,
    val items: List<String>
)

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

sealed interface Account {
    val id: String
}
data class AdminAccount(override val id: String, val permissions: String, val level: Int) : Account
data class GuestAccount(override val id: String, val sessionDuration: Long) : Account

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
        val isExpress = true
        val discount = 0.15
        val isCancelled = false
        val user = User("Alice", Address("New York", "10001"))
        val complexOrder = ComplexOrder("ORD-101", 650.0, "GOLD", isExpress, user, listOf("Laptop", "Mouse"))
        val nullUser: User? = null
        val nullAddressUser = User("Bob", null)

        val account: Account = AdminAccount("ADM-99", "ALL_ACCESS", 10)
        val guestAccount: Account = GuestAccount("GST-01", 3600L)
        val rawObject: Any = AdminAccount("ADM-77", "READ_ONLY", 2)

        val total = processor.calculateTotal(order)
        _lastOrderResult.value = "Order $orderId ($customerType): $$total"
    }
}
