package com.hrandika.angular.onedu.ui.login

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProviders
import com.hrandika.angular.onedu.R
import com.hrandika.angular.onedu.utils.crypto.BiometricPromptUtils
import com.hrandika.angular.onedu.utils.crypto.CIPHERTEXT_WRAPPER
import com.hrandika.angular.onedu.utils.crypto.CryptographyManager
import com.hrandika.angular.onedu.utils.crypto.SHARED_PREFS_FILENAME
import kotlinx.android.synthetic.main.activity_login.*

class LoginActivity : AppCompatActivity() {
    private val TAG = "AppCompatActivity"
    private lateinit var loginViewModel: LoginViewModel
    private lateinit var biometricPrompt: BiometricPrompt
    private lateinit var cryptographyManager: CryptographyManager
    private val ciphertextWrapper
        get() = cryptographyManager.getCiphertextWrapperFromSharedPrefs(
            applicationContext,
            SHARED_PREFS_FILENAME,
            Context.MODE_PRIVATE,
            CIPHERTEXT_WRAPPER
        )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_login)

        val username = findViewById<EditText>(R.id.username)
        val password = findViewById<EditText>(R.id.password)
        val login = findViewById<Button>(R.id.login)
        val loading = findViewById<ProgressBar>(R.id.loading)
        val useBio = findViewById<Button>(R.id.loginBio)

        useBio.setOnClickListener {
            this.showBiometricPromptForDecryption(LoggedInUserView("firsn name", "id"));
        }


        loginViewModel = ViewModelProviders.of(this, LoginViewModelFactory())
            .get(LoginViewModel::class.java)

        loginViewModel.loginFormState.observe(this@LoginActivity, Observer {
            val loginState = it ?: return@Observer

            // disable login button unless both username / password is valid
            login.isEnabled = loginState.isDataValid

            if (loginState.usernameError != null)
                username.error = getString(loginState.usernameError)

            if (loginState.passwordError != null)
                password.error = getString(loginState.passwordError)

        })

        loginViewModel.loginResult.observe(this@LoginActivity, Observer {
            val loginResult = it ?: return@Observer

            loading.visibility = View.GONE
            if (loginResult.error != null)
                showLoginFailed(loginResult.error)

            if (loginResult.success != null)
                updateUiWithUser(loginResult.success)

            setResult(Activity.RESULT_OK)
        })

        username.afterTextChanged {
            loginViewModel.loginDataChanged(
                username.text.toString(),
                password.text.toString()
            )
        }

        password.apply {
            afterTextChanged {
                loginViewModel.loginDataChanged(
                    username.text.toString(),
                    password.text.toString()
                )
            }

            setOnEditorActionListener { _, actionId, _ ->
                when (actionId) {
                    EditorInfo.IME_ACTION_DONE ->
                        loginViewModel.login(
                            username.text.toString(),
                            password.text.toString()
                        )
                }
                false
            }

            login.setOnClickListener {
                loading.visibility = View.VISIBLE
                loginViewModel.login(username.text.toString(), password.text.toString())
            }
        }
    }

    private fun updateUiWithUser(model: LoggedInUserView) {
        this.showBiometricPromptForEncryption(model);
//        this.showBiometricPromptForDecryption(model);
    }

    private fun showLoginFailed(@StringRes errorString: Int) {
        Toast.makeText(applicationContext, errorString, Toast.LENGTH_SHORT).show()
    }

    private fun showBiometricPromptForEncryption(model: LoggedInUserView) {
        val canAuthenticate = BiometricManager.from(applicationContext).canAuthenticate()
        if (canAuthenticate == BiometricManager.BIOMETRIC_SUCCESS) {
            val secretKeyName = getString(R.string.secret_key_name)
            cryptographyManager = CryptographyManager()
            val cipher = cryptographyManager.getInitializedCipherForEncryption(secretKeyName)
            val biometricPrompt =
                BiometricPromptUtils.createBiometricPrompt(
                    this,
                    model,
                    ::encryptAndStoreServerToken
                )
            val promptInfo = BiometricPromptUtils.createPromptInfo(this)
            biometricPrompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
        } else {
            Toast.makeText(
                applicationContext,
                "No Biometrics to setup",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun encryptAndStoreServerToken(
        authResult: BiometricPrompt.AuthenticationResult,
        model: LoggedInUserView
    ) {
        authResult.cryptoObject?.cipher?.apply {
//            Log.d(TAG, "The token from server is ${model.token}")
            val encryptedServerTokenWrapper = cryptographyManager.encryptData(model.token, this)
            cryptographyManager.persistCiphertextWrapperToSharedPrefs(
                encryptedServerTokenWrapper,
                applicationContext,
                SHARED_PREFS_FILENAME,
                Context.MODE_PRIVATE,
                CIPHERTEXT_WRAPPER
            )
        }
    }

    private fun showBiometricPromptForDecryption(model: LoggedInUserView) {
        if(ciphertextWrapper != null){
            val canAuthenticate = BiometricManager.from(applicationContext).canAuthenticate()
            if (canAuthenticate == BiometricManager.BIOMETRIC_SUCCESS) {
                ciphertextWrapper?.let { textWrapper ->
                    val secretKeyName = getString(R.string.secret_key_name)
                    val cipher = cryptographyManager.getInitializedCipherForDecryption(
                        secretKeyName,
                        textWrapper.initializationVector
                    )
                    var biometricPrompt =
                        BiometricPromptUtils.createBiometricPrompt(
                            this,
                            model,
                            ::decryptServerTokenFromStorage
                        )
                    val promptInfo = BiometricPromptUtils.createPromptInfo(this)
                    biometricPrompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
                }
            }
        }else{
            Toast.makeText(this, "You haven't logged in", Toast.LENGTH_LONG).show()
        }

    }

    private fun decryptServerTokenFromStorage(authResult: BiometricPrompt.AuthenticationResult, model: LoggedInUserView) {
        ciphertextWrapper?.let { textWrapper ->
            authResult.cryptoObject?.cipher?.let {
                val plaintext =
                    cryptographyManager.decryptData(textWrapper.ciphertext, it)
//                Log.d("decryption", plaintext)
//                username.setText("plaintext");
                Toast.makeText(this, "Decrypted token is $plaintext", Toast.LENGTH_LONG).show()
            }
        }
    }
}

/**
 * Extension function to simplify setting an afterTextChanged action to EditText components.
 */
fun EditText.afterTextChanged(afterTextChanged: (String) -> Unit) {
    this.addTextChangedListener(object : TextWatcher {
        override fun afterTextChanged(editable: Editable?) {
            afterTextChanged.invoke(editable.toString())
        }

        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}

        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
    })
}