package com.example.androtviptv.account

import android.content.Context
import com.google.android.gms.auth.api.signin.GoogleAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.android.gms.tasks.Task
import java.util.UUID

/**
 * Firebase account operations for KaiserTV+.
 *
 * UI is intentionally kept separate so the same account flow can be used on
 * touch devices and Android TV. Add google-services.json before initializing
 * Firebase in the application.
 */
class AccountRepository(context: Context) {
    private val appContext = context.applicationContext
    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
    private val functions = FirebaseFunctions.getInstance("us-central1")

    fun currentUser(): FirebaseUser? = auth.currentUser

    fun signInWithEmail(email: String, password: String): Task<FirebaseUser> =
        auth.signInWithEmailAndPassword(email.trim(), password)
            .continueWithTask { task ->
                if (!task.isSuccessful) throw task.exception ?: IllegalStateException("Giriş başarısız.")
                val user = task.result?.user ?: throw IllegalStateException("Kullanıcı bilgisi alınamadı.")
                user.reload().continueWith { reloadTask ->
                    if (!reloadTask.isSuccessful) throw reloadTask.exception ?: IllegalStateException("Hesap doğrulanamadı.")
                    user
                }
            }

    fun createAccount(email: String, password: String): Task<FirebaseUser> =
        auth.createUserWithEmailAndPassword(email.trim(), password)
            .continueWithTask { task ->
                if (!task.isSuccessful) throw task.exception ?: IllegalStateException("Hesap oluşturulamadı.")
                val user = task.result?.user ?: throw IllegalStateException("Kullanıcı bilgisi alınamadı.")
                user.sendEmailVerification().continueWith { verificationTask ->
                    if (!verificationTask.isSuccessful) throw verificationTask.exception
                        ?: IllegalStateException("Doğrulama e-postası gönderilemedi.")
                    user
                }
            }

    fun sendPasswordReset(email: String) = auth.sendPasswordResetEmail(email.trim())

    fun signInWithGoogleIdToken(idToken: String): Task<FirebaseUser> {
        require(idToken.isNotBlank()) { "Google kimlik belirteci boş olamaz." }
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        return auth.signInWithCredential(credential).continueWith { task ->
            if (!task.isSuccessful) throw task.exception ?: IllegalStateException("Google girişi başarısız.")
            task.result?.user ?: throw IllegalStateException("Kullanıcı bilgisi alınamadı.")
        }
    }

    fun linkGoogleToCurrentAccount(idToken: String): Task<FirebaseUser> {
        require(idToken.isNotBlank()) { "Google kimlik belirteci boş olamaz." }
        val user = auth.currentUser ?: throw IllegalStateException("Önce hesabınıza giriş yapın.")
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        return user.linkWithCredential(credential).continueWith { task ->
            if (!task.isSuccessful) throw task.exception ?: IllegalStateException("Google hesabı bağlanamadı.")
            task.result?.user ?: throw IllegalStateException("Kullanıcı bilgisi alınamadı.")
        }
    }

    fun registerThisDevice(deviceName: String, deviceType: String): Task<com.google.firebase.functions.HttpsCallableResult> {
        val user = auth.currentUser ?: throw IllegalStateException("Önce hesabınıza giriş yapın.")
        val prefs = appContext.getSharedPreferences("kaisertv_account", Context.MODE_PRIVATE)
        var deviceId = prefs.getString("device_id", null)
        if (deviceId.isNullOrBlank()) {
            deviceId = UUID.randomUUID().toString()
            prefs.edit().putString("device_id", deviceId).apply()
        }
        val payload = hashMapOf(
            "deviceId" to deviceId,
            "deviceName" to deviceName.take(80),
            "deviceType" to deviceType.take(24),
            "email" to (user.email ?: "")
        )
        return functions.getHttpsCallable("registerDevice").call(payload)
    }

    fun removeDevice(deviceId: String): Task<com.google.firebase.functions.HttpsCallableResult> {
        require(deviceId.isNotBlank()) { "Cihaz kimliği boş olamaz." }
        return functions.getHttpsCallable("removeDevice").call(mapOf("deviceId" to deviceId))
    }

    fun signOut() = auth.signOut()

    fun userDocument() = auth.currentUser?.let { firestore.collection("users").document(it.uid) }
}
