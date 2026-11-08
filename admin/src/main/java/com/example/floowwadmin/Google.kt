package com.example.floowwadmin

import android.app.Activity
import androidx.core.content.ContextCompat
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialManagerCallback
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

/** Login Google lewat Credential Manager. Hasilnya email akun yang dipilih. */
object Google {
    fun signIn(a: Activity, clientId: String, done: (email: String?, error: String?) -> Unit) {
        val opt = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(clientId)
            .build()
        val req = GetCredentialRequest.Builder().addCredentialOption(opt).build()
        CredentialManager.create(a).getCredentialAsync(
            a, req, null, ContextCompat.getMainExecutor(a),
            object : CredentialManagerCallback<GetCredentialResponse, GetCredentialException> {
                override fun onResult(result: GetCredentialResponse) {
                    val cred = result.credential
                    if (cred is CustomCredential && cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                        try {
                            done(GoogleIdTokenCredential.createFrom(cred.data).id, null)
                        } catch (e: Exception) {
                            done(null, "Data akun Google tidak terbaca.")
                        }
                    } else done(null, "Jenis kredensial tidak dikenal.")
                }

                override fun onError(e: GetCredentialException) {
                    done(null, "Gagal masuk: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        )
    }
}
