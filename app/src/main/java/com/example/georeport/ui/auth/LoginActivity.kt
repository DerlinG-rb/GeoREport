package com.example.georeport.ui.auth

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.georeport.R
import com.example.georeport.data.AuthValidator
import com.example.georeport.data.UserStore
import com.example.georeport.databinding.ActivityLoginBinding
import com.example.georeport.ui.home.HomeActivity

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var userStore: UserStore

    private val registerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.getStringExtra(RegisterActivity.EXTRA_EMAIL)?.let { email ->
                binding.etEmail.setText(email)
                binding.etPassword.text?.clear()
                binding.etPassword.requestFocus()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        userStore = UserStore(this)
        userStore.getSessionEmail()?.let { email ->
            openHome(email)
            return
        }

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
        binding.tvGoToRegister.setOnClickListener {
            registerLauncher.launch(Intent(this, RegisterActivity::class.java))
        }

        binding.btnLogin.setOnClickListener {
            attemptLogin()
        }

        binding.tvForgotPassword.setOnClickListener {
            Toast.makeText(
                this,
                getString(R.string.msg_forgot_password_info),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun attemptLogin() {
        val email = binding.etEmail.text?.toString()?.trim().orEmpty()
        val password = binding.etPassword.text?.toString().orEmpty()

        binding.tilEmail.error = null
        binding.tilPassword.error = null

        when {
            email.isEmpty() -> {
                binding.tilEmail.error = getString(R.string.msg_field_required)
                binding.etEmail.requestFocus()
            }
            !AuthValidator.isValidEmail(email) -> {
                binding.tilEmail.error = getString(R.string.msg_email_invalid)
                binding.etEmail.requestFocus()
            }
            password.isEmpty() -> {
                binding.tilPassword.error = getString(R.string.msg_field_required)
                binding.etPassword.requestFocus()
            }
            !userStore.isEmailRegistered(email) -> {
                binding.tilEmail.error = getString(R.string.msg_email_not_registered)
                binding.etEmail.requestFocus()
            }
            !userStore.login(email, password) -> {
                binding.tilPassword.error = getString(R.string.msg_wrong_password)
                binding.etPassword.requestFocus()
            }
            else -> {
                userStore.saveSession(email)
                Toast.makeText(
                    this,
                    getString(R.string.msg_login_success),
                    Toast.LENGTH_SHORT
                ).show()
                openHome(email)
            }
        }
    }

    private fun openHome(email: String) {
        val intent = Intent(this, HomeActivity::class.java).apply {
            putExtra(HomeActivity.EXTRA_USER_EMAIL, email)
        }
        startActivity(intent)
        finish()
    }
}
