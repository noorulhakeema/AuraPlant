package com.example.auraplantapp.data

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.YearMonth

class PlantViewModel(private val repo: PlantRepository) : ViewModel() {

    val today: StateFlow<DailyRecord?> = repo.todayRecord()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun month(month: YearMonth) = repo.month(month)

    // Hook these up to the UI / sensor / AI code.
    fun onLongPressWater() = viewModelScope.launch { repo.onWatered() }
    fun onLightOk() = viewModelScope.launch { repo.onLightOk() }
    fun onLightLevel(level: Int) = viewModelScope.launch { repo.onLightLevel(level) }
    fun onRecordedQuoteAnalysed(text: String, positive: Boolean, score: Float) =
        viewModelScope.launch { repo.onRecordedQuoteAnalysed(text, positive, score) }

    fun onQuoteTranscribed(text: String) = viewModelScope.launch { repo.onQuoteTranscribed(text) }
    fun onAccelerometerPat() = viewModelScope.launch { repo.onPat() }
    fun onAccelerometerDrop() = viewModelScope.launch { repo.onDrop() }
    fun onAiResult(positive: Boolean, score: Float) = viewModelScope.launch {
        if (positive) repo.onPositiveMessage(score)
    }
}

class PlantViewModelFactory(private val repo: PlantRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(PlantViewModel::class.java))
        return PlantViewModel(repo) as T
    }
}