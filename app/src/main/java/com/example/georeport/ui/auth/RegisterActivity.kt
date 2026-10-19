package com.example.georeport.ui.auth

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.georeport.R
import com.example.georeport.databinding.ActivityRegisterBinding

class RegisterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRegisterBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
    }

    private fun setupListeners() {
        // Return back to Login screen
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.tvGoToLogin.setOnClickListener {
            finish()
        }

        // Simulated Register Action (Visual only, no DB connection yet)
        binding.btnRegister.setOnClickListener {
            val fullName = binding.etFullName.text?.toString()?.trim().orEmpty()
            val email = binding.etRegisterEmail.text?.toString()?.trim().orEmpty()
            val phone = binding.etPhone.text?.toString()?.trim().orEmpty()
            val password = binding.etRegisterPassword.text?.toString()?.trim().orEmpty()
            val confirmPassword = binding.etConfirmPassword.text?.toString()?.trim().orEmpty()

            if (fullName.isEmpty() || email.isEmpty() || phone.isEmpty() || password.isEmpty() || confirmPassword.isEmpty()) {
                Toast.makeText(
                    this,
                    getString(R.string.msg_empty_fields),
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            if (password != confirmPassword) {
                Toast.makeText(
                    this,
                    getString(R.string.msg_password_mismatch),
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            // Visual confirmation of simulated account creation
            Toast.makeText(
                this,
                getString(R.string.msg_register_success),
                Toast.LENGTH_LONG
            ).show()

            // Return to login after simulated registration
            finish()
        }
    }
}
