package com.example.meshrelay

import android.util.Base64
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * IDENTITY DECIDES THE CEILING.
 *
 * Anyone can shout "help". Only the organiser can say "everyone move."
 *
 * A fake "EVERYONE MOVE TO GATE 7" in a packed crowd is a stampede generator, and it is
 * the one message type in this system that could kill somebody. So it is the one type
 * that must be signed.
 *
 * Creating a signature needs the private key. Checking one needs only the public key.
 * Every phone ships with the public half below and can therefore *verify* an order but
 * never *produce* one.
 *
 * ECDSA on P-256, not Ed25519: Ed25519 needs Android 13, and the Nokia is older.
 * P-256 has been in Android since the beginning. Plan.md 8.5 is explicit that showing
 * the distinction matters more than the choice of cipher.
 */
object Authority {

    /**
     * The organiser's PUBLIC key. Safe to ship - it can only check signatures.
     * The matching private key is NEVER in this app. It is typed into the one
     * designated command phone at runtime, which is a thing you can prove on stage:
     * hand a judge the APK and let them look.
     */
    private const val PUBLIC_KEY_B64 =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAESyzUZPgX0LQeN4875YctEaSfTCdjtuYueYK8Lz+" +
            "lB1y/2N/Pqg1hKG8klt8aTf2XjBaGC6VC6GBaLP1c7sV+YA=="

    /**
     * What actually gets signed.
     *
     * Only the fields that must never change: who, what kind, when, and the words.
     * NOT ttl, copies or path - those change at every single hop, so signing them
     * would make the signature fail the moment the message moved, which is exactly
     * when it needs to still be checkable.
     *
     * Change one character of the text - Gate 7 to Gate 3 - and this stops matching.
     */
    private fun signedBytes(m: MeshMessage): ByteArray =
        listOf(m.id, m.origin, m.type.name, m.createdAt.toString(), m.text)
            .joinToString("")
            .toByteArray(Charsets.UTF_8)

    private val publicKey: PublicKey by lazy {
        KeyFactory.getInstance("EC").generatePublic(
            X509EncodedKeySpec(Base64.decode(PUBLIC_KEY_B64, Base64.NO_WRAP))
        )
    }

    /** True only if this message was signed by the organiser's key, unaltered. */
    fun verify(m: MeshMessage): Boolean {
        val sig = m.sig ?: return false
        return try {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(publicKey)
                update(signedBytes(m))
                verify(Base64.decode(sig, Base64.NO_WRAP))
            }
        } catch (e: Exception) {
            // A malformed or forged signature is simply "not signed". Never a crash,
            // and never treated as valid because something went wrong.
            false
        }
    }

    /** Parses a pasted private key. Returns null if it is not a usable key. */
    fun parsePrivateKey(b64: String): PrivateKey? = try {
        KeyFactory.getInstance("EC").generatePrivate(
            PKCS8EncodedKeySpec(Base64.decode(b64.trim(), Base64.NO_WRAP))
        )
    } catch (e: Exception) {
        null
    }

    /** Only the command phone can do this, because only it has been given the key. */
    fun sign(m: MeshMessage, key: PrivateKey): String? = try {
        Signature.getInstance("SHA256withECDSA").run {
            initSign(key)
            update(signedBytes(m))
            Base64.encodeToString(sign(), Base64.NO_WRAP)
        }
    } catch (e: Exception) {
        null
    }
}
