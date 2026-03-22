package com.follett.keyboard.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.switchMap
import androidx.lifecycle.viewModelScope
import com.follett.keyboard.data.model.KeystrokeLog
import com.follett.keyboard.data.repository.KeyboardRepository
import com.follett.keyboard.data.repository.KeyboardStats
import kotlinx.coroutines.launch

class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = KeyboardRepository(application)

    private val _searchQuery = MutableLiveData("")
    val searchQuery: LiveData<String> = _searchQuery

    val logs: LiveData<List<KeystrokeLog>> = _searchQuery.switchMap { query ->
        if (query.isBlank()) repository.allWordEvents
        else repository.searchWords(query)
    }

    private val _stats = MutableLiveData<KeyboardStats>()
    val stats: LiveData<KeyboardStats> = _stats

    private val _exportData = MutableLiveData<List<KeystrokeLog>>()
    val exportData: LiveData<List<KeystrokeLog>> = _exportData

    private val _clearComplete = MutableLiveData<Boolean>()
    val clearComplete: LiveData<Boolean> = _clearComplete

    init {
        loadStats()
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun loadStats() {
        viewModelScope.launch {
            _stats.value = repository.getStats()
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            repository.clearAll()
            _clearComplete.value = true
            loadStats()
        }
    }

    fun prepareExport() {
        viewModelScope.launch {
            _exportData.value = repository.getAllForExport()
        }
    }
}
