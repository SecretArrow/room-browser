package com.roombrowser.security

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Biometric profile lock (spec section 20). Uses the AndroidX Biometric
 * Prompt: BIOMETRIC_WEAK + device credential fallback so a locked profile
 * can be opened with fingerprint/face OR the device PIN/pattern/password.
 */
object BiometricGate {

    fun canAuthenticate(activity: FragmentActivity): Boolean =
        BiometricManager.from(activity).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        ) == BiometricManager.BIOMETRIC_SUCCESS

    /**
     * Gate a locked profile. [onSuccess] unlocks, [onFailure] keeps the
     * profile locked (never silently unlocks).
     */
    fun unlock(
        activity: FragmentActivity,
        profileName: String,
        onSuccess: () -> Unit,
        onFailure: () -> Unit
    ) {
        val manager = BiometricManager.from(activity)
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (manager.canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            // No biometrics/credential available — fall back to a manual gate
            // handled by the caller (profile stays locked).
            onFailure()
            return
        }
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onFailure()
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock \"$profileName\"")
            .setSubtitle("Authentication is required to open this profile")
            .setAllowedAuthenticators(authenticators)
            .build()
        prompt.authenticate(info)
    }
}
