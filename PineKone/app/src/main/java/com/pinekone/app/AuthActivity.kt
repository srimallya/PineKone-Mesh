package com.pinekone.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.pinekone.app.auth.AuthRepository
import com.pinekone.app.databinding.ActivityAuthBinding
import kotlinx.coroutines.launch

class AuthActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAuthBinding
    private lateinit var authRepository: AuthRepository
    private var pinConfigured = false
    private var biometricPromptInfo: BiometricPrompt.PromptInfo? = null
    private lateinit var biometricPrompt: BiometricPrompt

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)

        authRepository = (application as PineKoneApp).authRepository
        setupBiometricPrompt()

        binding.authSubmitButton.setOnClickListener { handlePinAction() }

        lifecycleScope.launch {
            pinConfigured = authRepository.isPinSet()
            updateUiForState()
            configureBiometricAvailability()
        }
    }

    private fun handlePinAction() {
        val pin = binding.pinInput.text?.toString().orEmpty()
        binding.pinInputLayout.error = null
        binding.confirmPinInputLayout.error = null

        if (!pin.all { it.isDigit() } || pin.length < PIN_MIN_LENGTH) {
            binding.pinInputLayout.error = getString(R.string.auth_pin_requirements)
            return
        }

        if (pinConfigured) {
            lifecycleScope.launch {
                val valid = authRepository.verifyPin(pin)
                if (valid) {
                    unlock()
                } else {
                    binding.pinInputLayout.error = getString(R.string.auth_pin_incorrect)
                }
            }
        } else {
            val confirmPin = binding.confirmPinInput.text?.toString().orEmpty()
            if (pin != confirmPin) {
                binding.confirmPinInputLayout.error = getString(R.string.auth_pin_mismatch)
                return
            }
            lifecycleScope.launch {
                authRepository.savePin(pin)
                pinConfigured = true
                unlock()
            }
        }
    }

    private fun updateUiForState() {
        if (pinConfigured) {
            binding.title.text = getString(R.string.auth_unlock_title)
            binding.authDescription.text = getString(R.string.auth_description_unlock)
            binding.pinInputLayout.hint = getString(R.string.auth_enter_pin)
            binding.confirmPinInputLayout.isVisible = false
            binding.authSubmitButton.text = getString(R.string.auth_unlock)
            binding.pinInput.setText("")
        } else {
            binding.title.text = getString(R.string.auth_create_pin_title)
            binding.authDescription.text = getString(R.string.auth_description_setup)
            binding.pinInputLayout.hint = getString(R.string.auth_new_pin_hint)
            binding.confirmPinInputLayout.hint = getString(R.string.auth_confirm_pin_hint)
            binding.confirmPinInputLayout.isVisible = true
            binding.authSubmitButton.text = getString(R.string.auth_set_pin)
            binding.pinInput.setText("")
            binding.confirmPinInput.setText("")
        }
    }

    private fun setupBiometricPrompt() {
        biometricPrompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    unlock()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.ERROR_CANCELED
                    ) {
                        Snackbar.make(binding.root, errString, Snackbar.LENGTH_SHORT).show()
                    }
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    Snackbar.make(binding.root, R.string.auth_biometric_failed, Snackbar.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun configureBiometricAvailability() {
        val canAuthenticate = BiometricManager.from(this).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )
        val available = pinConfigured && canAuthenticate == BiometricManager.BIOMETRIC_SUCCESS
        binding.biometricButton.isVisible = available
        if (available) {
            biometricPromptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.auth_biometric_title))
                .setSubtitle(getString(R.string.auth_biometric_subtitle))
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
                .build()
            binding.biometricButton.setOnClickListener {
                biometricPromptInfo?.let { biometricPrompt.authenticate(it) }
            }
        }
    }

    private fun unlock() {
        binding.pinInput.clearFocus()
        val intent = Intent(this, HomeActivity::class.java)
        startActivity(intent)
        finish()
    }

    companion object {
        private const val PIN_MIN_LENGTH = 4
    }
}
