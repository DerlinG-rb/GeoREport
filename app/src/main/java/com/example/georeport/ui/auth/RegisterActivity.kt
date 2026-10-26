package com.example.georeport.ui.auth

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.georeport.R
import com.example.georeport.data.AuthValidator
import com.example.georeport.data.UserStore
import com.example.georeport.databinding.ActivityRegisterBinding

class RegisterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRegisterBinding
    private lateinit var userStore: UserStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        userStore = UserStore(this)
        applySystemBarInsets()
        setupListeners()
    }

    private fun applySystemBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            WindowInsetsControllerCompat(window, window.decorView).apply {
                isAppearanceLightStatusBars = true
                isAppearanceLightNavigationBars = true
            }
        }
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener { finish() }
        binding.tvGoToLogin.setOnClickListener { finish() }

        binding.btnRegister.setOnClickListener {
            attemptRegister()
        }
    }

    private fun attemptRegister() {
        val fullName = binding.etFullName.text?.toString()?.trim().orEmpty()
        val email = binding.etRegisterEmail.text?.toString()?.trim().orEmpty()
        val phone = binding.etPhone.text?.toString()?.trim().orEmpty()
        val password = binding.etRegisterPassword.text?.toString().orEmpty()
        val confirmPassword = binding.etConfirmPassword.text?.toString().orEmpty()

        clearErrors()

        when {
            fullName.isEmpty() -> {
                binding.tilFullName.error = getString(R.string.msg_name_required)
                binding.etFullName.requestFocus()
            }
            email.isEmpty() -> {
                binding.tilRegisterEmail.error = getString(R.string.msg_field_required)
                binding.etRegisterEmail.requestFocus()
            }
            !AuthValidator.isValidEmail(email) -> {
                binding.tilRegisterEmail.error = getString(R.string.msg_email_invalid)
                binding.etRegisterEmail.requestFocus()
            }
            phone.isEmpty() -> {
                binding.tilPhone.error = getString(R.string.msg_field_required)
                binding.etPhone.requestFocus()
            }
            !AuthValidator.isValidPhone(phone) -> {
                binding.tilPhone.error = getString(R.string.msg_phone_invalid)
                binding.etPhone.requestFocus()
            }
            password.isEmpty() -> {
                binding.tilRegisterPassword.error = getString(R.string.msg_field_required)
                binding.etRegisterPassword.requestFocus()
            }
            !AuthValidator.isValidPassword(password) -> {
                binding.tilRegisterPassword.error = getString(R.string.msg_password_too_short)
                binding.etRegisterPassword.requestFocus()
            }
            confirmPassword.isEmpty() -> {
                binding.tilConfirmPassword.error = getString(R.string.msg_field_required)
                binding.etConfirmPassword.requestFocus()
            }
            password != confirmPassword -> {
                binding.tilConfirmPassword.error = getString(R.string.msg_password_mismatch)
                binding.etConfirmPassword.requestFocus()
            }
            userStore.isEmailRegistered(email) -> {
                binding.tilRegisterEmail.error = getString(R.string.msg_email_registered)
                binding.etRegisterEmail.requestFocus()
            }
            !userStore.register(fullName, email, phone, password) -> {
                Toast.makeText(
                    this,
                    getString(R.string.msg_register_save_error),
                    Toast.LENGTH_LONG
                ).show()
            }
            else -> {
                Toast.makeText(
                    this,
                    getString(R.string.msg_register_success),
                    Toast.LENGTH_LONG
                ).show()

                setResult(
                    RESULT_OK,
                    Intent().putExtra(EXTRA_EMAIL, email.trim().lowercase())
                )
                finish()
            }
        }
    }

    private fun clearErrors() {
        binding.tilFullName.error = null
        binding.tilRegisterEmail.error = null
        binding.tilPhone.error = null
        binding.tilRegisterPassword.error = null
        binding.tilConfirmPassword.error = null
    }

    companion object {
        const val EXTRA_EMAIL = "EXTRA_EMAIL"
    }
}
