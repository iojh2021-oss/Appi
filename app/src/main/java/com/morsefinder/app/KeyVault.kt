package com.morsefinder.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM key that lives in the Android Keystore (hardware-backed where the phone has a TEE/StrongBox).
 * Used to protect data at rest: the post-quantum identity (private keys) and the taught hand samples.
 */
object KeyVault {
    private const val ALIAS = "mf_vault_v1"
    private val FILE_MAGIC = "MFE1".toByteArray(Charsets.US_ASCII)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return kg.generateKey()
    }

    /** iv(12) || ciphertext+tag */
    fun wrap(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return c.iv + c.doFinal(plain)
    }

    fun unwrap(blob: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
        return c.doFinal(blob, 12, blob.size - 12)
    }

    /** Writes `MFE1 || wrap(text)`. Falls back to plain text only if the Keystore is broken on this phone. */
    fun writeText(f: File, text: String) {
        val bytes = try { FILE_MAGIC + wrap(text.toByteArray(Charsets.UTF_8)) } catch (e: Exception) { text.toByteArray(Charsets.UTF_8) }
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) { f.writeBytes(bytes); tmp.delete() }   // atomic replace when possible
    }

    /** Reads files written by [writeText] and also old plain-text files (transparent migration). */
    fun readText(f: File): String {
        val b = f.readBytes()
        if (b.size > 4 && b.copyOfRange(0, 4).contentEquals(FILE_MAGIC))
            return String(unwrap(b.copyOfRange(4, b.size)), Charsets.UTF_8)
        return String(b, Charsets.UTF_8)
    }
}

/** Device identity + the stored recipient key for sealed sample packs. */
class PqStore(private val ctx: Context) {
    private val idFile get() = File(ctx.filesDir, "pq_identity.bin")
    private val rcptFile get() = File(ctx.filesDir, "pq_recipient.mfkey")

    @Synchronized
    fun identity(): PqCrypto.Identity {
        if (idFile.exists()) {
            try { return PqCrypto.decodeIdentity(KeyVault.unwrap(idFile.readBytes())) } catch (e: Exception) { /* regenerate below */ }
        }
        val id = PqCrypto.newIdentity()
        val tmp = File(ctx.filesDir, "pq_identity.tmp")
        tmp.writeBytes(KeyVault.wrap(PqCrypto.encodeIdentity(id)))      // no plaintext fallback for private keys
        if (!tmp.renameTo(idFile)) { idFile.writeBytes(tmp.readBytes()); tmp.delete() }
        return id
    }

    fun myFingerprint(): String = identity().publicBundle().fingerprint()

    /** File to share with people who want to send me a sealed pack. */
    fun publicKeyFile(): File {
        val f = File(ctx.cacheDir, "my_key.mfkey")
        f.writeBytes(PqCrypto.encodePublic(identity().publicBundle()))
        return f
    }

    @Synchronized
    fun setRecipient(bytes: ByteArray): String {
        val p = PqCrypto.decodePublic(bytes)           // validates
        rcptFile.writeBytes(PqCrypto.encodePublic(p))
        return p.fingerprint()
    }

    fun recipient(): PqCrypto.PublicBundle? = try {
        if (rcptFile.exists()) PqCrypto.decodePublic(rcptFile.readBytes()) else null
    } catch (e: Exception) { null }

    fun sealFor(plain: ByteArray): File? {
        val to = recipient() ?: return null
        val f = File(ctx.cacheDir, "sign_pack.mfpack")
        f.writeBytes(PqCrypto.seal(plain, to, identity()))
        return f
    }

    fun open(blob: ByteArray): PqCrypto.Opened = PqCrypto.open(blob, identity())
}
