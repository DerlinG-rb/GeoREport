package com.example.georeport.ui.auth

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.georeport.R
import com.example.georeport.databinding.ActivityLoginBinding

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
    }

    private fun setupListeners() {
        // Redirection to Register Activity when user indicates they don't have an account
        binding.tvGoToRegister.setOnClickListener {
            val intent = Intent(this, RegisterActivity::class.java)
            startActivity(intent)
        }

        // Simulated Login Action (Visual only, no DB connection yet)
        binding.btnLogin.setOnClickListener {
            val email = binding.etEmail.text?.toString()?.trim().orEmpty()
            val password = binding.etPassword.text?.toString()?.trim().orEmpty()

            if (email.isEmpty() || password.isEmpty()) {
                Toast.makeText(
                    this,
                    getString(R.string.msg_empty_fields),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(
                    this,
                    "${getString(R.string.msg_login_success)}: $email",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        // Forgot password simulated action
        binding.tvForgotPassword.setOnClickListener {
            Toast.makeText(
                this,
                getString(R.string.msg_forgot_password_info),
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}
