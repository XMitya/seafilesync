package com.xmitya.seafilesync.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.xmitya.seafilesync.R
import com.xmitya.seafilesync.ui.LoginUiState

const val LOGIN_SERVER_TAG = "login-server"
const val LOGIN_EMAIL_TAG = "login-email"
const val LOGIN_PASSWORD_TAG = "login-password"
const val LOGIN_OTP_TAG = "login-otp"
const val LOGIN_SUBMIT_TAG = "login-submit"

@Composable
fun LoginScreen(
    state: LoginUiState,
    onServerUrlChanged: (String) -> Unit,
    onEmailChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onOtpChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val canSubmit = state.serverUrl.isNotBlank() &&
        state.email.isNotBlank() &&
        state.password.isNotBlank() &&
        !state.isSubmitting

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.login_title),
            style = MaterialTheme.typography.headlineSmall,
        )

        OutlinedTextField(
            value = state.serverUrl,
            onValueChange = onServerUrlChanged,
            label = { Text(stringResource(R.string.login_server)) },
            placeholder = { Text(stringResource(R.string.login_server_hint)) },
            singleLine = true,
            enabled = !state.isSubmitting,
            // The scheme is optional; SeafileApi.normalize fills in https when it is missing.
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth().testTag(LOGIN_SERVER_TAG),
        )

        OutlinedTextField(
            value = state.email,
            onValueChange = onEmailChanged,
            label = { Text(stringResource(R.string.login_email)) },
            singleLine = true,
            enabled = !state.isSubmitting,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth().testTag(LOGIN_EMAIL_TAG),
        )

        OutlinedTextField(
            value = state.password,
            onValueChange = onPasswordChanged,
            label = { Text(stringResource(R.string.login_password)) },
            singleLine = true,
            enabled = !state.isSubmitting,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth().testTag(LOGIN_PASSWORD_TAG),
        )

        // Only appears once the server has actually asked for a code, so accounts without
        // two-factor never see the field.
        if (state.needsOtp) {
            OutlinedTextField(
                value = state.otp,
                onValueChange = onOtpChanged,
                label = { Text(stringResource(R.string.login_otp)) },
                singleLine = true,
                enabled = !state.isSubmitting,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth().testTag(LOGIN_OTP_TAG),
            )
        }

        state.errorMessage?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Button(
            onClick = onSubmit,
            enabled = canSubmit,
            modifier = Modifier.fillMaxWidth().testTag(LOGIN_SUBMIT_TAG),
        ) {
            if (state.isSubmitting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text(stringResource(R.string.login_submit))
            }
        }
    }
}
