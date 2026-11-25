package com.ilyemir.antena

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ilyemir.antena.data.ChannelRepository
import com.ilyemir.antena.data.db.ChannelEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AntenaViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ChannelRepository(application)

    /** null = veritabanı henüz okunmadı; boş liste = kurulum gerekli. */
    val channels: StateFlow<List<ChannelEntity>?> = repository.channels
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _lastChannelNumber = MutableStateFlow(1)
    val lastChannelNumber: StateFlow<Int> = _lastChannelNumber

    val m3uUrl: StateFlow<String> = repository.m3uUrl
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    sealed interface ImportState {
        data object Idle : ImportState
        data object Loading : ImportState
        data class Error(val message: String) : ImportState
        data class Done(val count: Int) : ImportState
    }

    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState

    init {
        viewModelScope.launch {
            repository.lastChannelNumber.collect { _lastChannelNumber.value = it }
        }
    }

    fun importPlaylist(url: String) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            _importState.value = ImportState.Loading
            _importState.value = repository.importFromUrl(trimmed).fold(
                onSuccess = { ImportState.Done(it) },
                onFailure = { ImportState.Error(it.message ?: "İçe aktarma başarısız") },
            )
        }
    }

    fun setLastChannelNumber(number: Int) {
        viewModelScope.launch { repository.setLastChannelNumber(number) }
    }
}
