package au.edu.unimelb.floraguide.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import au.edu.unimelb.floraguide.domain.repository.AuthState

@Composable
fun AuthScreen(
    authState: AuthState,
    onSignIn: (String, String) -> Unit,
    onRegister: (String, String, String) -> Unit,
    onAnonymousSignIn: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isRegistering by rememberSaveable { mutableStateOf(false) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var displayName by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "FloraGuide Account",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Cloud Sync & Field Guide Security",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        when (authState) {
            is AuthState.Authenticated -> {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text("Signed In", style = MaterialTheme.typography.labelLarge)
                        Text(
                            text = authState.user.displayName ?: authState.user.email ?: "Anonymous User",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "UID: ${authState.user.uid}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
                            Text("Sign Out")
                        }
                    }
                }
            }

            is AuthState.Authenticating -> {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(8.dp))
                Text("Authenticating session...")
            }

            else -> {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (authState is AuthState.Error) {
                            Text(
                                text = authState.message,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        if (isRegistering) {
                            OutlinedTextField(
                                value = displayName,
                                onValueChange = { displayName = it }, // <-- Change from { displayName = it } if it wasn't assigning correctly or was empty
                                label = { Text("Display Name") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it }, // <-- This ensures the typed characters update the state
                            label = { Text("Email Address") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it }, // <-- This ensures the typed characters update the state
                            label = { Text("Password") },
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Button(
                            onClick = {
                                if (isRegistering) onRegister(email, password, displayName)
                                else onSignIn(email, password)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (isRegistering) "Create Account" else "Sign In")
                        }

                        TextButton(
                            onClick = { isRegistering = !isRegistering },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (isRegistering) "Already have an account? Sign In" else "Need an account? Register")
                        }

                        HorizontalDivider()

                        OutlinedButton(
                            onClick = onAnonymousSignIn,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Continue as Anonymous Guest")
                        }
                    }
                }
            }
        }
    }
}
