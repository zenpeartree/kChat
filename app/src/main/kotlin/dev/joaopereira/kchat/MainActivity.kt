package dev.joaopereira.kchat

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.joaopereira.kchat.auth.TwitchAuthManager
import dev.joaopereira.kchat.auth.DeviceCodeSession
import dev.joaopereira.kchat.chat.ChatConnectionState
import dev.joaopereira.kchat.chat.ChatUiState
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber

class MainActivity : AppCompatActivity() {
    private val container by lazy { (application as TwitchChatApplication).container }

    private lateinit var clientIdInput: EditText
    private lateinit var channelInput: EditText
    private lateinit var statusText: TextView
    private lateinit var accountText: TextView
    private lateinit var messageCountText: TextView
    private lateinit var activationTitleText: TextView
    private lateinit var activationCodeText: TextView
    private lateinit var activationUrlText: TextView
    private lateinit var openActivationButton: Button
    private lateinit var connectButton: Button
    private lateinit var saveButton: Button
    private lateinit var signOutButton: Button
    private var authorizationJob: Job? = null
    private var deviceCodeSession: DeviceCodeSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        clientIdInput = findViewById(R.id.client_id_input)
        channelInput = findViewById(R.id.channel_input)
        statusText = findViewById(R.id.status_text)
        accountText = findViewById(R.id.account_text)
        messageCountText = findViewById(R.id.message_count_text)
        activationTitleText = findViewById(R.id.activation_title_text)
        activationCodeText = findViewById(R.id.activation_code_text)
        activationUrlText = findViewById(R.id.activation_url_text)
        openActivationButton = findViewById(R.id.open_activation_button)
        connectButton = findViewById(R.id.connect_button)
        saveButton = findViewById(R.id.save_button)
        signOutButton = findViewById(R.id.sign_out_button)

        val settings = container.settingsStore.currentSettings()
        clientIdInput.setText(settings.clientId)
        channelInput.setText(settings.channelLogin)

        saveButton.setOnClickListener {
            container.settingsStore.update(
                clientId = clientIdInput.text.toString(),
                channelLogin = channelInput.text.toString(),
            )
            container.chatRepository.reconnectNow()
        }

        connectButton.setOnClickListener {
            val clientId = clientIdInput.text.toString().trim()
            if (clientId.isBlank()) {
                statusText.setText(R.string.status_missing_client_id)
                return@setOnClickListener
            }

            container.settingsStore.update(
                clientId = clientId,
                channelLogin = channelInput.text.toString(),
            )
            startDeviceFlow(clientId)
        }

        signOutButton.setOnClickListener {
            authorizationJob?.cancel()
            deviceCodeSession = null
            container.authManager.clearAuthState()
            container.chatRepository.reconnectNow()
            renderActivation(null)
        }
        openActivationButton.setOnClickListener {
            deviceCodeSession?.verificationUri?.let { openActivationPage(it) }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.chatRepository.uiState.collect(::render)
            }
        }
    }

    override fun onDestroy() {
        authorizationJob?.cancel()
        super.onDestroy()
    }

    private fun startDeviceFlow(clientId: String) {
        authorizationJob?.cancel()
        authorizationJob = lifecycleScope.launch {
            try {
                val session = container.authManager.startDeviceAuthorization(clientId)
                deviceCodeSession = session
                renderActivation(session)
                statusText.text = getString(R.string.status_waiting_approval)
                container.authManager.completeDeviceAuthorization(clientId, session)
                deviceCodeSession = null
                renderActivation(null)
                statusText.text = getString(R.string.status_device_login_complete)
                container.chatRepository.reconnectNow()
            } catch (error: Exception) {
                Timber.e(error, "Twitch device login failed")
                statusText.text = error.message ?: "${error::class.java.simpleName}: ${getString(R.string.status_auth_failed)}"
            }
        }
    }

    private fun renderActivation(session: DeviceCodeSession?) {
        val visible = session != null
        activationTitleText.visibility = if (visible) View.VISIBLE else View.GONE
        activationCodeText.visibility = if (visible) View.VISIBLE else View.GONE
        activationUrlText.visibility = if (visible) View.VISIBLE else View.GONE
        openActivationButton.visibility = if (visible) View.VISIBLE else View.GONE
        if (session != null) {
            activationCodeText.text = getString(R.string.activation_code_format, session.userCode)
            activationUrlText.text = getString(R.string.activation_url_format, session.verificationUri)
        }
    }

    private fun openActivationPage(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        }
    }

    private fun render(state: ChatUiState) {
        statusText.text = state.status
        accountText.text = when {
            state.authenticatedUser != null -> getString(R.string.account_format, state.authenticatedUser)
            container.authManager.isAuthorized() -> getString(R.string.account_connected)
            else -> getString(R.string.account_disconnected)
        }
        messageCountText.text = getString(R.string.message_count_format, state.messages.size)
        signOutButton.isEnabled = container.authManager.isAuthorized()
        connectButton.text = if (container.authManager.isAuthorized()) {
            getString(R.string.action_reconnect_twitch)
        } else {
            getString(R.string.action_connect_twitch)
        }
        if (state.connectionState == ChatConnectionState.NEEDS_CONFIGURATION) {
            statusText.setText(R.string.status_missing_client_id)
        }
        if (container.authManager.isAuthorized()) {
            renderActivation(null)
        }
    }

    companion object {
        fun launchIntent(context: Context): Intent {
            return Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        }
    }
}
