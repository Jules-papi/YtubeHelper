package com.example.youtubeoverlay.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VideoUrlRepository @Inject constructor() {
    private val _lastSharedUrl = MutableStateFlow<String?>(null)
    val lastSharedUrl: StateFlow<String?> = _lastSharedUrl.asStateFlow()

    fun updateUrl(url: String) {
        _lastSharedUrl.value = url
    }
}
