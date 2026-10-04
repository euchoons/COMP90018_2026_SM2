package au.edu.unimelb.floraguide.ui.components

import au.edu.unimelb.floraguide.domain.model.SensorSnapshot

/**
 * What the Observe screen says about capturing, derived in one place so the stability pill,
 * the hint, the switch summary and the shutter state cannot contradict each other.
 */
data class CaptureGuidance(
    val shutterEnabled: Boolean,
    val hint: String,
    val stabilityLabel: String,
    val stabilityPositive: Boolean,
    val switchSummary: String,
)

fun captureGuidance(
    snapshot: SensorSnapshot,
    gateEnabled: Boolean,
    consentPending: Boolean,
): CaptureGuidance {
    val canGate = snapshot.canMeasureStability
    val steady = snapshot.isStable
    val gating = gateEnabled && canGate
    return CaptureGuidance(
        shutterEnabled = !consentPending && (!gating || steady),
        hint = when {
            consentPending -> "Choose whether to use online identification"
            !canGate -> "Manual capture: motion sensors unavailable"
            gating && !steady -> "Hold still before capturing"
            gating -> "Ready to capture"
            steady -> "Ready to capture (stability gate off)"
            else -> "Moving: photo may blur (stability gate off)"
        },
        stabilityLabel = when {
            !canGate -> "Stability n/a"
            steady -> "Steady"
            // "Hold still" is an instruction, so show it only while the gate enforces it.
            gating -> "Hold still"
            else -> "Moving"
        },
        stabilityPositive = canGate && steady,
        switchSummary = when {
            !canGate -> "Sensor unavailable: manual capture"
            gateEnabled -> "Accelerometer + gyroscope: shutter waits until the phone is steady"
            else -> "Off: shutter always enabled, photos may blur"
        },
    )
}
