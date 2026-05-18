package com.neuromesh.crisis.domain.consensus

import com.neuromesh.crisis.data.model.CrisisType
import com.neuromesh.crisis.data.model.SeverityLevel
import com.neuromesh.crisis.data.model.SituationAssessment
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VotingStrategy @Inject constructor() {

    fun computeConsensus(assessments: List<SituationAssessment>): ConsensusResult {
        if (assessments.isEmpty()) return ConsensusResult.NoConsensus

        val totalWeight = assessments.sumOf { confidenceWeight(it.confidence) }

        val crisisVotes = mutableMapOf<CrisisType, Double>()
        val severityVotes = mutableMapOf<SeverityLevel, Double>()

        assessments.forEach { a ->
            val w = confidenceWeight(a.confidence)
            crisisVotes[a.crisisType] = (crisisVotes[a.crisisType] ?: 0.0) + w
            severityVotes[a.severity] = (severityVotes[a.severity] ?: 0.0) + w
        }

        val dominantCrisis = crisisVotes.maxByOrNull { it.value }!!
        val dominantSeverity = severityVotes.maxByOrNull { it.value }!!

        val crisisRatio = dominantCrisis.value / totalWeight
        val severityRatio = dominantSeverity.value / totalWeight

        val avgConfidence = assessments.map { it.confidence }.average().toFloat()
        val agreedOnCrisis = crisisRatio >= CONSENSUS_THRESHOLD
        val agreedOnSeverity = severityRatio >= CONSENSUS_THRESHOLD

        return if (agreedOnCrisis && dominantCrisis.key != CrisisType.UNKNOWN) {
            ConsensusResult.Reached(
                crisisType = dominantCrisis.key,
                severity = dominantSeverity.key,
                confidence = avgConfidence * crisisRatio.toFloat(),
                agreementRatio = crisisRatio.toFloat(),
                contributingDevices = assessments.map { it.deviceId },
                immediateRisks = mergeRisks(assessments)
            )
        } else {
            ConsensusResult.Disputed(
                topCrisisType = dominantCrisis.key,
                agreementRatio = crisisRatio.toFloat(),
                avgConfidence = avgConfidence.toDouble()
            )
        }
    }

    private fun confidenceWeight(confidence: Float): Double = confidence.toDouble().coerceIn(0.0, 1.0)

    private fun mergeRisks(assessments: List<SituationAssessment>): List<String> {
        val riskFrequency = mutableMapOf<String, Int>()
        assessments.forEach { a ->
            a.immediateRisks.forEach { risk ->
                riskFrequency[risk] = (riskFrequency[risk] ?: 0) + 1
            }
        }
        return riskFrequency.entries
            .sortedByDescending { it.value }
            .take(5)
            .map { it.key }
    }

    companion object {
        const val CONSENSUS_THRESHOLD = 0.6
        const val MIN_DEVICES_FOR_CONSENSUS = 2
    }
}

sealed class ConsensusResult {
    data class Reached(
        val crisisType: CrisisType,
        val severity: SeverityLevel,
        val confidence: Float,
        val agreementRatio: Float,
        val contributingDevices: List<String>,
        val immediateRisks: List<String>
    ) : ConsensusResult()

    data class Disputed(
        val topCrisisType: CrisisType,
        val agreementRatio: Float,
        val avgConfidence: Double
    ) : ConsensusResult()

    object NoConsensus : ConsensusResult()
}