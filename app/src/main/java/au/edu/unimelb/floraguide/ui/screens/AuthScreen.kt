package au.edu.unimelb.floraguide.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import au.edu.unimelb.floraguide.domain.repository.AuthState

@Composable
fun AuthScreen(
    authState: AuthState,
    onSignIn: (String, String) -> Unit,
    onRegister: (String, String, String) -> Unit,
    onAnonymousSignIn: () -> Unit,
    onContinueOffline: () -> Unit,
    onImportLocal: () -> Unit,
    onRetrySync: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isRegistering by rememberSaveable { mutableStateOf(false) }
    var email by rememberSaveable { mutableStateOf("") }
    // Passwords must not be persisted in the saved-instance-state bundle.
    var password by remember { mutableStateOf("") }
    var displayName by rememberSaveable { mutableStateOf("") }
    var confirmGuestSignOut by remember { mutableStateOf(false) }
    var confirmImport by remember { mutableStateOf(false) }
    val user = (authState as? AuthState.Authenticated)?.user
    val upgrading = user?.isAnonymous == true
    val registering = upgrading || isRegistering

    if (confirmImport) {
        AlertDialog(
            onDismissRequest = { confirmImport = false },
            title = { Text("Import into this account?") },
            text = { Text("Local guest observations have no stored account owner. Import only if these are yours. They will move out of the device's guest field guide and sync to this account.") },
            confirmButton = { TextButton(onClick = { confirmImport = false; onImportLocal() }) { Text("Import") } },
            dismissButton = { TextButton(onClick = { confirmImport = false }) { Text("Cancel") } },
        )
    }

    if (confirmGuestSignOut) {
        AlertDialog(
            onDismissRequest = { confirmGuestSignOut = false },
            title = { Text("Leave this cloud guest?") },
            text = { Text("Without upgrading first, you cannot sign back into this anonymous account. Its observations will not be shown under a different account.") },
            confirmButton = { TextButton(onClick = { confirmGuestSignOut = false; onSignOut() }) { Text("Sign out") } },
            dismissButton = { TextButton(onClick = { confirmGuestSignOut = false }) { Text("Keep guest") } },
        )
    }

    Column(
        modifier = modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("FloraGuide Account", style = MaterialTheme.typography.headlineMedium)
        Text("Observations stay on this device while offline. Signed-in accounts also sync to the cloud.")

        if (authState == AuthState.Authenticating) {
            CircularProgressIndicator()
            Text("Signing in…")
            return@Column
        }

        if (user != null) {
            Text(
                if (upgrading) "Anonymous cloud guest" else user.displayName ?: user.email ?: "Signed in",
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedButton(onClick = { confirmImport = true }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
                Text("Import local guest observations")
            }
            OutlinedButton(onClick = onRetrySync, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) { Text("Retry cloud sync") }
            if (upgrading) {
                Text("Create an account below to keep this guest's observations under the same account.")
            } else {
                Button(onClick = onSignOut) { Text("Sign out") }
                return@Column
            }
        }
        if (authState == AuthState.OfflineGuest) {
            Text("Local guest mode · the guided demo works without an internet connection.")
            Text("After signing in, use Import local guest observations in Account to move these records into your account.")
        }
        if (authState is AuthState.Error) {
            Text(authState.message, color = MaterialTheme.colorScheme.error)
        }
        if (registering) {
            OutlinedTextField(
                value = displayName, onValueChange = { displayName = it },
                label = { Text("Display name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedTextField(
            value = email, onValueChange = { email = it }, label = { Text("Email address") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password, onValueChange = { password = it }, label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                if (registering) onRegister(email, password, displayName) else onSignIn(email, password)
                password = ""
            },
            enabled = email.isNotBlank() && password.isNotBlank() && (!registering || displayName.isNotBlank()),
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
        ) {
            Text(if (upgrading) "Upgrade guest account" else if (registering) "Create account" else "Sign in")
        }
        if (!upgrading) {
            TextButton(onClick = { isRegistering = !isRegistering }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
                Text(if (isRegistering) "Already have an account? Sign in" else "Need an account? Register")
            }
            OutlinedButton(onClick = onAnonymousSignIn, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
                Text("Continue as cloud guest (internet required)")
            }
            if (authState != AuthState.OfflineGuest) {
                OutlinedButton(onClick = onContinueOffline, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
                    Text("Continue offline · guided demo")
                }
            }
        } else {
            Text("To use a different existing account, sign out first. This guest's cloud records stay with the guest.")
            TextButton(onClick = { confirmGuestSignOut = true }) { Text("Sign out") }
        }
    }
}
