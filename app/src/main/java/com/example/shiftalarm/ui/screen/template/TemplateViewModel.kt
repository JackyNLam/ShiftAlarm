package com.example.shiftalarm.ui.screen.template

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.shiftalarm.data.entity.ShiftTemplate
import com.example.shiftalarm.data.repository.ShiftRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class TemplateListUiState(
    val templates: List<ShiftTemplate> = emptyList(),
    val isLoading: Boolean = true,
    val deleteConfirmTemplate: ShiftTemplate? = null,
    val affectedScheduleCount: Int = 0
)

class TemplateViewModel(
    private val repository: ShiftRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(TemplateListUiState())
    val uiState: StateFlow<TemplateListUiState> = _uiState.asStateFlow()

    init {
        loadTemplates()
    }

    private fun loadTemplates() {
        viewModelScope.launch {
            val templates = repository.getAllTemplates()
            _uiState.value = _uiState.value.copy(
                templates = templates,
                isLoading = false
            )
        }
    }

    fun refresh() {
        loadTemplates()
    }

    fun deleteTemplate(template: ShiftTemplate) {
        viewModelScope.launch {
            val count = repository.countFutureSchedulesByTemplate(template.id)
            if (count > 0) {
                _uiState.value = _uiState.value.copy(
                    deleteConfirmTemplate = template,
                    affectedScheduleCount = count
                )
            } else {
                repository.deleteTemplateById(template.id)
                loadTemplates()
            }
        }
    }

    fun confirmDelete() {
        viewModelScope.launch {
            _uiState.value.deleteConfirmTemplate?.let {
                repository.deleteTemplateById(it.id)
            }
            _uiState.value = _uiState.value.copy(
                deleteConfirmTemplate = null,
                affectedScheduleCount = 0
            )
            loadTemplates()
        }
    }

    fun cancelDelete() {
        _uiState.value = _uiState.value.copy(
            deleteConfirmTemplate = null,
            affectedScheduleCount = 0
        )
    }

    fun duplicateTemplate(template: ShiftTemplate) {
        viewModelScope.launch {
            val copy = template.copy(
                id = 0L,
                name = "${template.name} (Copy)"
            )
            repository.saveTemplate(copy)
            loadTemplates()
        }
    }

    fun moveTemplate(fromIndex: Int, toIndex: Int) {
        viewModelScope.launch {
            val list = _uiState.value.templates.toMutableList()
            if (fromIndex in list.indices && toIndex in list.indices) {
                val item = list.removeAt(fromIndex)
                list.add(toIndex, item)
                list.forEachIndexed { index, template ->
                    repository.updateTemplate(template.copy(sortOrder = index))
                }
                _uiState.value = _uiState.value.copy(templates = list)
            }
        }
    }

    class Factory(
        private val repository: ShiftRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return TemplateViewModel(repository) as T
        }
    }
}

// --- Edit Template ViewModel ---

data class TemplateEditUiState(
    val isEditing: Boolean = false,
    val name: String = "",
    val shiftLabel: String = "",
    val alarmTimes: List<String> = listOf(""),
    val location: String = "",
    val color: Long = 0xFF42A5F5L,
    val sortOrder: Int = 0,
    val isLoading: Boolean = false,
    val saved: Boolean = false
)

class TemplateEditViewModel(
    private val repository: ShiftRepository,
    private val templateId: Long?
) : ViewModel() {

    private val _uiState = MutableStateFlow(TemplateEditUiState())
    val uiState: StateFlow<TemplateEditUiState> = _uiState.asStateFlow()

    init {
        if (templateId != null && templateId > 0) {
            loadTemplate(templateId)
        }
    }

    private fun loadTemplate(id: Long) {
        viewModelScope.launch {
            val template = repository.getTemplateById(id) ?: return@launch
            _uiState.value = TemplateEditUiState(
                isEditing = true,
                name = template.name,
                shiftLabel = template.shiftLabel,
                alarmTimes = if (template.alarmTimes.isEmpty()) listOf("") else template.alarmTimes,
                location = template.location,
                color = template.color,
                sortOrder = template.sortOrder
            )
        }
    }

    fun updateName(name: String) {
        _uiState.value = _uiState.value.copy(name = name)
    }

    fun updateShiftLabel(label: String) {
        _uiState.value = _uiState.value.copy(shiftLabel = label)
    }

    fun updateLocation(location: String) {
        _uiState.value = _uiState.value.copy(location = location)
    }

    fun updateColor(color: Long) {
        _uiState.value = _uiState.value.copy(color = color)
    }

    fun updateAlarmTime(index: Int, time: String) {
        val list = _uiState.value.alarmTimes.toMutableList()
        if (index in list.indices) {
            list[index] = time
            _uiState.value = _uiState.value.copy(alarmTimes = list)
        }
    }

    fun addAlarmTime() {
        _uiState.value = _uiState.value.copy(
            alarmTimes = _uiState.value.alarmTimes + ""
        )
    }

    fun removeAlarmTime(index: Int) {
        val list = _uiState.value.alarmTimes.toMutableList()
        if (list.size > 1 && index in list.indices) {
            list.removeAt(index)
            _uiState.value = _uiState.value.copy(alarmTimes = list)
        }
    }

    fun resetSaved() {
        _uiState.value = _uiState.value.copy(saved = false)
    }

    fun save() {
        val s = _uiState.value
        if (s.name.isBlank()) return

        viewModelScope.launch {
            val validTimes = s.alarmTimes.filter { it.matches(Regex("^\\d{2}:\\d{2}$")) }
            val template = ShiftTemplate(
                id = if (s.isEditing) (templateId ?: 0L) else 0L,
                name = s.name.trim(),
                shiftLabel = s.shiftLabel.trim(),
                alarmTimes = validTimes,
                location = s.location.trim(),
                color = s.color,
                sortOrder = s.sortOrder
            )
            if (s.isEditing) {
                repository.updateTemplate(template)
            } else {
                repository.saveTemplate(template)
            }
            _uiState.value = _uiState.value.copy(saved = true)
        }
    }

    class Factory(
        private val repository: ShiftRepository,
        private val templateId: Long?
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return TemplateEditViewModel(repository, templateId) as T
        }
    }
}