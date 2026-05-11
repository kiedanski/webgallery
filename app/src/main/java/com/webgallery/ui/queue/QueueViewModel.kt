// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 WebGallery contributors
package com.webgallery.ui.queue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.webgallery.data.PhotoRepository
import com.webgallery.data.db.MutationEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class QueueViewModel(
    private val repository: PhotoRepository
) : ViewModel() {

    val mutations: StateFlow<List<MutationEntity>> = repository.getAllMutations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun retry(mutationId: Long) {
        viewModelScope.launch { repository.retryMutation(mutationId) }
    }

    fun discard(mutationId: Long) {
        viewModelScope.launch { repository.discardMutation(mutationId) }
    }
}
