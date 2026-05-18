package com.neuromesh.crisis.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.neuromesh.crisis.data.model.*
import com.neuromesh.crisis.data.repository.MeshRepository
import com.neuromesh.crisis.data.repository.ObservationRepository
import com.neuromesh.crisis.domain.consensus.ConsensusEngine
import com.neuromesh.crisis.domain.consensus.ConsensusEvent
import com.neuromesh.crisis.domain.consensus.ConsensusResult
import com.neuromesh.crisis.domain.usecase.DetectCrisisUseCase
import com.neuromesh.crisis.domain.usecase.GenerateAlertUseCase
import com.neuromesh.crisis.domain.usecase.ShareObservationUseCase
import com.neuromesh.crisis.infrastructure.ml.Gemma4ModelRunner
import com.neuromesh.crisis.infrastructure.network.MeshNetworkManager
import com.neuromesh.crisis.infrastructure.network.MessageSerializer
import com.neuromesh.crisis.infrastructure.sensor.EnvironmentalSensorManager
import com.neuromesh.crisis.util.Constants
import com.neuromesh.crisis.util.DeviceCapability
import com.neuromesh.crisis.util.Logger
import com.neuromesh.crisis.util.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val detectCrisisUseCase: DetectCrisisUseCase,
    private val generateAlertUseCase: GenerateAlertUseCase,
    private val shareObservationUseCase: ShareObservationUseCase,
    private val modelRunner: Gemma4ModelRunner,
    private val meshNetworkManager: MeshNetworkManager,
    private val messageSerializer: MessageSerializer,
    private val consensusEngine: ConsensusEngine,
    private val meshRepository: MeshRepository,
    private val observationRepository: ObservationRepository,
    private val deviceCapability: DeviceCapability,
    private val environmentalSensorManager: EnvironmentalSensorManager
) : ViewModel() {

    private val _uiState = MutableStateFlow<UiState>(UiState.Initializing)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _activeAlert = MutableStateFlow<CrisisAlert?>(null)
    val activeAlert: StateFlow<CrisisAlert?> = _activeAlert.asStateFlow()

    private val _latestAssessment = MutableStateFlow<SituationAssessment?>(null)
    val latestAssessment: StateFlow<SituationAssessment?> = _latestAssessment.asStateFlow()

    val peers: StateFlow<List<MeshPeer>> = meshRepository.peers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val connectedPeerCount: StateFlow<Int> = meshRepository.connectedPeers
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private var detectionJob: Job? = null
    private var meshJob: Job? = null

    init {
        initialize()
    }

    private fun initialize() {
        viewModelScope.launch {
            _uiState.value = UiState.Initializing

            val ramMb = deviceCapability.totalRamMb()
            val canHostLlm = deviceCapability.canHostLlm()
            Logger.i(TAG, "Device RAM: ${ramMb}MB, canHostLlm=$canHostLlm")

            environmentalSensorManager.startListening()
            _uiState.value = UiState.Ready
            startMesh()
            startDetectionLoop()
            collectMeshMessages()
            collectConsensusEvents()

            // DEFERRED LLM LOAD:
            // High memory pressure during app startup is the main cause of the
            // "black screen + close" crash on 2-6GB devices. By waiting until
            // the UI is ready and sensors are active, we avoid the startup
            // resource spike.
            if (canHostLlm) {
                Logger.i(TAG, "Waiting for system stabilization before LLM init...")
                delay(3000)

                if (ramMb < 2500L) {
                    Logger.i(TAG, "Aggressive memory prep for low-RAM device")
                    System.gc()
                    delay(1000)
                }

                when (val result = modelRunner.initialize()) {
                    is Result.Success -> Logger.d(TAG, "Model initialized successfully")
                    is Result.Error -> Logger.w(
                        TAG,
                        "Model init failed (${result.message}); running heuristic-only mode"
                    )
                }
            }
        }
    }

    private fun startDetectionLoop() {
        detectionJob?.cancel()
        detectionJob = viewModelScope.launch {
            while (isActive) {
                runDetection()
                delay(Constants.DETECTION_INTERVAL_MS)
            }
        }
    }

    private suspend fun runDetection() {
        if (_uiState.value !is UiState.Ready && _uiState.value !is UiState.Monitoring) return

        _uiState.value = UiState.Detecting

        when (val result = detectCrisisUseCase()) {
            is Result.Success -> {
                val assessment = result.data
                _latestAssessment.value = assessment

                if (assessment.confidence >= Constants.MIN_ALERT_CONFIDENCE &&
                    assessment.crisisType != CrisisType.UNKNOWN
                ) {
                    Logger.i(TAG, "Crisis detected: ${assessment.crisisType} (${assessment.confidence})")

                    shareObservationUseCase.shareAssessment(assessment)

                    when (val alertResult = generateAlertUseCase(assessment)) {
                        is Result.Success -> {
                            _activeAlert.value = alertResult.data
                            _uiState.value = UiState.CrisisDetected(alertResult.data)
                        }
                        is Result.Error -> {
                            _uiState.value = UiState.Monitoring
                        }
                    }
                } else {
                    _uiState.value = UiState.Monitoring
                }
            }
            is Result.Error -> {
                // NO_SIGNAL is the normal idle case (sensor gate not crossed):
                // stay quietly in Monitoring, never surface it as an error.
                _uiState.value = UiState.Monitoring
            }
        }
    }

    private fun startMesh() {
        meshJob?.cancel()
        meshJob = viewModelScope.launch {
            meshNetworkManager.start()
        }
    }

    private fun collectMeshMessages() {
        viewModelScope.launch {
            meshNetworkManager.incomingMessages.collect { message ->
                when (message.type) {
                    MessageType.ASSESSMENT -> {
                        val assessment = messageSerializer.decodeAssessment(message.payload) ?: return@collect
                        shareObservationUseCase.receiveAssessment(assessment, this)
                    }
                    MessageType.OBSERVATION -> {
                        val observation = messageSerializer.decodeObservation(message.payload) ?: return@collect
                        observationRepository.save(observation)
                    }
                    MessageType.ALERT -> {
                        val alert = messageSerializer.decodeAlert(message.payload) ?: return@collect
                        if (_activeAlert.value == null || alert.severity.value > (_activeAlert.value?.severity?.value ?: 0)) {
                            _activeAlert.value = alert
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    private fun collectConsensusEvents() {
        viewModelScope.launch {
            consensusEngine.consensusEvents.collect { event ->
                when (event) {
                    is ConsensusEvent.ConsensusReached -> {
                        val consensusAssessment = consensusEngine.buildConsensusAssessment(
                            event.result, event.assessments
                        )
                        _latestAssessment.value = consensusAssessment

                        if (event.result.confidence >= Constants.MIN_ALERT_CONFIDENCE) {
                            when (val alertResult = generateAlertUseCase(consensusAssessment)) {
                                is Result.Success -> {
                                    _activeAlert.value = alertResult.data
                                    _uiState.value = UiState.CrisisDetected(alertResult.data)
                                }
                                else -> {}
                            }
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    fun dismissAlert() {
        _activeAlert.value = null
        _uiState.value = UiState.Monitoring
    }

    fun retryInitialization() {
        initialize()
    }

    override fun onCleared() {
        super.onCleared()
        detectionJob?.cancel()
        meshJob?.cancel()
        environmentalSensorManager.stopListening()
        meshNetworkManager.stop()
        modelRunner.close()
    }

    companion object {
        private const val TAG = "MainViewModel"
    }
}

sealed class UiState {
    object Initializing : UiState()
    object ModelNotFound : UiState()
    object Ready : UiState()
    object Monitoring : UiState()
    object Detecting : UiState()
    data class CrisisDetected(val alert: CrisisAlert) : UiState()
    data class Error(val message: String) : UiState()
}
