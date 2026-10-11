package io.openshm.patcher.data

import android.content.Context
import androidx.credentials.CreatePasswordRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPasswordOption
import androidx.credentials.PasswordCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import kotlin.coroutines.cancellation.CancellationException

/** A password chosen in the system prompt; [password] is wiped by the receiver. */
class PickedPassword(val password: CharArray, val credentialId: String)

/**
 * The system password manager (Credential Manager; Google Password Manager below Android 14).
 * [context] must be an Activity: the prompts are system UI shown over it.
 *
 * Security notes: the library only accepts and returns passwords as [String], which cannot be
 * overwritten; those copies are as short lived as the call. Nothing is logged and failures carry no text.
 */
class CredentialManagerPasswords(private val context: Context) : PasswordSaver {
    private val manager by lazy { CredentialManager.create(context) }

    override suspend fun save(id: String, password: CharArray): PasswordSaveResult =
        try {
            manager.createCredential(context, CreatePasswordRequest(id, String(password)))
            PasswordSaveResult.Saved
        } catch (e: CancellationException) {
            throw e
        } catch (e: CreateCredentialCancellationException) {
            PasswordSaveResult.Declined
        } catch (e: Exception) {
            // No provider or a provider failure: not saved, and worth another try later.
            PasswordSaveResult.Unavailable
        }

    /** The password (a char array the caller wipes) and credential id the user picks in the system prompt; null when none. */
    suspend fun pick(): PickedPassword? =
        try {
            val response = manager.getCredential(context, GetCredentialRequest(listOf(GetPasswordOption())))
            (response.credential as? PasswordCredential)?.let { PickedPassword(it.password.toCharArray(), it.id) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
}
