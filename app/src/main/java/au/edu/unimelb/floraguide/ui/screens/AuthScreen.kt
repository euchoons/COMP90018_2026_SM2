package au.edu.unimelb.floraguide.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import au.edu.unimelb.floraguide.domain.repository.AuthState
import androidx.compose.foundation.Image
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import au.edu.unimelb.floraguide.R
import androidx.compose.ui.tooling.preview.Preview


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
    onDeleteAccount: () -> Unit,
    modifier: Modifier = Modifier,

) {
    var confirmAccountDeletion by remember { mutableStateOf(false) }
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

    if (confirmAccountDeletion) {
        AlertDialog(
            onDismissRequest = { confirmAccountDeletion = false },
            title = { Text("Delete Account?") },
            text = { Text("This will permanently delete your account, all saved observations, and cloud photos. This action cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmAccountDeletion = false
                    onDeleteAccount()
                }) { Text("Delete Permanently", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmAccountDeletion = false }) { Text("Cancel") } }
        )
    }

    Column(
        modifier = modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher),
            contentDescription = "FloraGuide logo",
            modifier = Modifier
                .size(44.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text("FloraGuide Account", style = MaterialTheme.typography.headlineMedium)
        }
        Spacer(modifier = Modifier.height(48.dp))

        if (authState == AuthState.Authenticating) {
            // This Box fills the remaining screen height and centers its contents
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator()
                    Text("Signing in…")
                }
            }
            return@Column
        }

        if (user != null) {
            // Updated Welcome Text
            Text(
                text = "Welcome, ${if (upgrading) "Anonymous cloud guest" else user.displayName ?: user.email ?: "Signed in"} !",
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 24.dp, bottom = 24.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.headlineLarge
            )
            OutlinedButton(onClick = { confirmImport = true }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
                border = null) {
                Text("Import local guest observations")
            }
            OutlinedButton(onClick = onRetrySync, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
                border = null) { Text("Retry cloud sync") }

            OutlinedButton(
                onClick = { confirmAccountDeletion = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0xFFFB2C36).copy(alpha = 0.2f), contentColor = Color(0xFFe7000b)),
                shape = RoundedCornerShape(10.dp),
                border = null
            ) {
                Text("Delete account and all data")
            }

            if (upgrading) {
                Spacer(modifier = Modifier.height(16.dp))
                Text("Create an account below to keep this guest's observations under the same account.", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(16.dp))
            } else {
                Button(onClick = onSignOut) { Text("Sign out") }
                return@Column
            }
//            OutlinedButton(
//                onClick = { confirmAccountDeletion = true },
//                modifier = Modifier.fillMaxWidth(),
//                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
//            ) {
//                Text("Delete account and all data")
//            }


        }
        if (authState is AuthState.Error) {
            Text(authState.message, color = MaterialTheme.colorScheme.error)
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            if (registering) {
                OutlinedTextField(
                    value = displayName, onValueChange = { displayName = it },
                    shape = MaterialTheme.shapes.medium,
                    placeholder = { Text("Display Name", fontSize = 14.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedPlaceholderColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        disabledBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                        errorBorderColor = MaterialTheme.colorScheme.error,
                        unfocusedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        focusedContainerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
            }
        OutlinedTextField(
            value = email, onValueChange = { email = it },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            singleLine = true, modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = MaterialTheme.shapes.medium,
            placeholder = { Text("Email", fontSize = 14.sp) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedPlaceholderColor = MaterialTheme.colorScheme.onSecondaryContainer,
                unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSecondaryContainer,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                disabledBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                errorBorderColor = MaterialTheme.colorScheme.error,
                unfocusedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                focusedContainerColor = MaterialTheme.colorScheme.primaryContainer
            ),

        )
        OutlinedTextField(
            value = password, onValueChange = { password = it }, placeholder = { Text("Password", fontSize = 14.sp) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true, modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = MaterialTheme.shapes.medium,
            colors = OutlinedTextFieldDefaults.colors(
                focusedPlaceholderColor = MaterialTheme.colorScheme.onSecondaryContainer,
                unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSecondaryContainer,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                disabledBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                errorBorderColor = MaterialTheme.colorScheme.error,
                unfocusedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                focusedContainerColor = MaterialTheme.colorScheme.primaryContainer
            ),

        )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = {
                if (registering) onRegister(email, password, displayName) else onSignIn(email, password)
                password = ""
            },
            enabled = email.isNotBlank() && password.isNotBlank() && (!registering || displayName.isNotBlank()),
            modifier = Modifier.fillMaxWidth().alpha(if (email.isNotBlank() && password.isNotBlank() && (!registering || displayName.isNotBlank())) 1f else 0.8f).height(48.dp),
            shape = MaterialTheme.shapes.medium,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                disabledContainerColor = MaterialTheme.colorScheme.primary,
                disabledContentColor =MaterialTheme.colorScheme.onPrimary
            ),
        ) {
            Text(if (upgrading) "Upgrade guest account" else if (registering) "Create account" else "Sign in")
        }
        if (!upgrading) {
            TextButton(onClick = { isRegistering = !isRegistering }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
                Text(if (isRegistering) "Already have an account? Sign in" else "Need an account? Register")
            }
            Spacer(modifier = Modifier.height(20.dp))
            OutlinedButton(onClick = onAnonymousSignIn, modifier = Modifier.fillMaxWidth().height(48.dp), shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
                border = null) {
                Text("Continue as Guest (Internet Required)")
            }
            if (authState != AuthState.OfflineGuest) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(onClick = onContinueOffline, modifier = Modifier.fillMaxWidth().height(48.dp), shape = MaterialTheme.shapes.medium,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                    border = null) {
                    Text("Continue Offline (Demo)")
                }
            }
        } else {
            Spacer(modifier = Modifier.height(12.dp))
            TextButton(onClick = { confirmGuestSignOut = true },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    disabledContainerColor = MaterialTheme.colorScheme.primary,
                    disabledContentColor =MaterialTheme.colorScheme.onPrimary
                ),) { Text("Sign out") }
        }

        Spacer(modifier = Modifier.height(20.dp))
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Note: Your saved plant entries remain securely stored on this phone when signed in as a Guest. However, your findings will be lost if the app is deleted.  \n\n Changed your mind and signed up? \n\n You can import your saved local observations into it at a later time", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            if (authState == AuthState.OfflineGuest) {
                Text("After signing in, use Import local guest observations in Account to move these records into your account.", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                Text("Currently sign in as local guest.", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            }
        }

    }
}
@Preview(showBackground = true)
@Composable
fun AuthScreenPreview() {
    au.edu.unimelb.floraguide.ui.theme.FloraGuideTheme {
        AuthScreen(
            authState = AuthState.Unauthenticated,
            onSignIn = { _, _ -> },
            onRegister = { _, _, _ -> },
            onAnonymousSignIn = {},
            onContinueOffline = {},
            onImportLocal = {},
            onRetrySync = {},
            onSignOut = {},
            onDeleteAccount = {}
        )
    }
}
