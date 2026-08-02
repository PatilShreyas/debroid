package com.example.sampledebugapp.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sampledebugapp.data.DataRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class MainScreenViewModel(private val dataRepository: DataRepository) : ViewModel() {

    val orderStatus: StateFlow<String> = dataRepository.lastOrderResult
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Initializing...")

    fun onProcessOrderClicked(customerType: String, amount: Double) {
        dataRepository.processOrder(customerType, amount)
    }
}
