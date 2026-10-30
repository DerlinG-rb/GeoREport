package com.example.georeport.ui.auth

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.georeport.ui.common.fitScreenContent
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

        // Usa el mismo almacenamiento local que consulta la pantalla de inicio de sesión.
        userStore = UserStore(this)
        fitScreenContent(binding.root)
        setupListeners()
    }

    // Volver cierra esta pantalla; Registrarse inicia la validación del formulario.
    private fun setupListeners() {
        binding.btnBack.setOnClickListener { finish() }
        binding.tvGoToLogin.setOnClickListener { finish() }

        binding.btnRegister.setOnClickListener {
            attemptRegister()
        }
    }

    // Recoge los datos. La contraseña conserva sus espacios tal como el usuario la escribió.
    private fun attemptRegister() {
        val fullName = binding.etFullName.text?.toString()?.trim().orEmpty()
        val email = binding.etRegisterEmail.text?.toString()?.trim().orEmpty()
        val phone = binding.etPhone.text?.toString()?.trim().orEmpty()
        val password = binding.etRegisterPassword.text?.toString().orEmpty()
        val confirmPassword = binding.etConfirmPassword.text?.toString().orEmpty()

        clearErrors()

        // Se muestra el primer error: campos, formatos, confirmación o correo duplicado.
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
            // Si los datos son válidos, guarda nombre, teléfono, sal y hash de contraseña.
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

                // Devuelve el correo a Login para rellenarlo; registrar no inicia la sesión.
                setResult(
                    RESULT_OK,
                    Intent().putExtra(EXTRA_EMAIL, email.trim().lowercase())
                )
                finish()
            }
        }
    }

    // Quita los mensajes anteriores antes de revisar de nuevo el formulario.
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
