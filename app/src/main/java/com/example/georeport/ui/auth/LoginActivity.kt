package com.example.georeport.ui.auth

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.georeport.ui.common.fitScreenContent
import com.example.georeport.R
import com.example.georeport.data.AuthValidator
import com.example.georeport.data.UserStore
import com.example.georeport.databinding.ActivityLoginBinding
import com.example.georeport.ui.home.HomeActivity

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var userStore: UserStore

    // Al volver de un registro exitoso, rellena el correo y deja la contraseña vacía.
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

        // UserStore accede a las cuentas y a la sesión guardadas localmente.
        userStore = UserStore(this)
        // Si ya se recordó una sesión, se abre Home sin mostrar el formulario.
        userStore.getSessionEmail()?.let { email ->
            openHome(email)
            return
        }

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        fitScreenContent(binding.root)
        setupListeners()
    }

    // Conecta los botones del formulario con registro, acceso y aviso de recuperación.
    private fun setupListeners() {
        binding.tvGoToRegister.setOnClickListener {
            registerLauncher.launch(Intent(this, RegisterActivity::class.java))
        }

        binding.btnLogin.setOnClickListener {
            attemptLogin()
        }

        // Actualmente muestra un aviso; este botón no cambia ni recupera contraseñas.
        binding.tvForgotPassword.setOnClickListener {
            Toast.makeText(
                this,
                getString(R.string.msg_forgot_password_info),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // Lee el formulario y muestra el primer error encontrado antes de permitir el acceso.
    private fun attemptLogin() {
        val email = binding.etEmail.text?.toString()?.trim().orEmpty()
        val password = binding.etPassword.text?.toString().orEmpty()

        // Limpia los errores del intento anterior.
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
            // Tras validar los campos, busca la cuenta en el almacenamiento local.
            !userStore.isEmailRegistered(email) -> {
                binding.tilEmail.error = getString(R.string.msg_email_not_registered)
                binding.etEmail.requestFocus()
            }
            // UserStore compara el hash de la contraseña introducida con el guardado.
            !userStore.login(email, password) -> {
                binding.tilPassword.error = getString(R.string.msg_wrong_password)
                binding.etPassword.requestFocus()
            }
            else -> {
                // Solo se recuerda la sesión después de verificar las credenciales.
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

    // Envía el correo a Home y cierra Login para que Atrás no regrese al formulario.
    private fun openHome(email: String) {
        val intent = Intent(this, HomeActivity::class.java).apply {
            putExtra(HomeActivity.EXTRA_USER_EMAIL, email)
        }
        startActivity(intent)
        finish()
    }
}
