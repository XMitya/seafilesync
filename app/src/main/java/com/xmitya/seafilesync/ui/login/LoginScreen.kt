package com.xmitya.seafilesync.ui.login

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
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
const val LOGIN_SCHEME_HTTPS_TAG = "login-scheme-https"
const val LOGIN_SCHEME_HTTP_TAG = "login-scheme-http"
const val LOGIN_INSECURE_TLS_TAG = "login-insecure-tls"
const val LOGIN_EMAIL_TAG = "login-email"
const val LOGIN_PASSWORD_TAG = "login-password"
const val LOGIN_OTP_TAG = "login-otp"
const val LOGIN_SUBMIT_TAG = "login-submit"

@Composable
fun LoginScreen(
    state: LoginUiState,
    onServerUrlChanged: (String) -> Unit,
    onUseHttpsChanged: (Boolean) -> Unit,
    onAllowInsecureTlsChanged: (Boolean) -> Unit,
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

        Text(
            text = stringResource(R.string.login_scheme_label),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // The scheme lives here rather than in the address field, so that what is shown and what
        // is dialled cannot drift apart. A pasted URL moves its scheme into this control.
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = state.useHttps,
                onClick = { onUseHttpsChanged(true) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                enabled = !state.isSubmitting,
                modifier = Modifier.testTag(LOGIN_SCHEME_HTTPS_TAG),
            ) {
                Text(stringResource(R.string.login_scheme_https))
            }
            SegmentedButton(
                selected = !state.useHttps,
                onClick = { onUseHttpsChanged(false) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                enabled = !state.isSubmitting,
                modifier = Modifier.testTag(LOGIN_SCHEME_HTTP_TAG),
            ) {
                Text(stringResource(R.string.login_scheme_http))
            }
        }

        OutlinedTextField(
            value = state.serverUrl,
            onValueChange = onServerUrlChanged,
            label = { Text(stringResource(R.string.login_server)) },
            placeholder = { Text(stringResource(R.string.login_server_hint)) },
            singleLine = true,
            enabled = !state.isSubmitting,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth().testTag(LOGIN_SERVER_TAG),
        )

        if (state.useHttps) {
            // Only over https is there a certificate to skip, and a self-signed one is the usual
            // reason a server on a home network cannot be reached at all.
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !state.isSubmitting) {
                        onAllowInsecureTlsChanged(!state.allowInsecureTls)
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.insecure_tls_title),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = state.allowInsecureTls,
                    onCheckedChange = onAllowInsecureTlsChanged,
                    enabled = !state.isSubmitting,
                    modifier = Modifier.testTag(LOGIN_INSECURE_TLS_TAG),
                )
            }
        } else {
            Text(
                text = stringResource(R.string.login_http_explanation),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

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
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.NumberPassword,
                    imeAction = ImeAction.Done,
                ),
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
