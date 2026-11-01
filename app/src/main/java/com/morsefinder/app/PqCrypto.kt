package com.morsefinder.app

import org.bouncycastle.jcajce.SecretKeyWithEncapsulation
import org.bouncycastle.jcajce.spec.KEMExtractSpec
import org.bouncycastle.jcajce.spec.KEMGenerateSpec
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.pqc.jcajce.provider.BouncyCastlePQCProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Provider
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Post-quantum "sealed pack" format used to share sign-language sample packs.
 *
 *  - key establishment : HYBRID  ML-KEM-768 (FIPS 203)  +  X25519   -> secrets are concatenated and run
 *                        through HKDF-SHA256, so the pack stays safe if EITHER primitive is broken
 *  - payload           : AES-256-GCM (symmetric; Grover only halves it to ~128-bit, still fine)
 *  - authenticity      : ML-DSA-65 (FIPS 204) signature by the sender over the whole container
 *
 * Pure JVM + JCA (BouncyCastle providers are used as instances, never registered globally, so they
 * can't clash with Android's stripped-down built-in "BC"). No Android classes -> unit-testable.
 *
 * NOTE: nothing is ever silently downgraded: if the PQ algorithms are not available the calls throw.
 */
object PqCrypto {
    private val bc: Provider by lazy { BouncyCastleProvider() }
    private val pqc: Provider by lazy { BouncyCastlePQCProvider() }
    private val rnd = SecureRandom()

    private val MAGIC = "MFP1".toByteArray(Charsets.US_ASCII)
    private const val SUITE: Int = 1                      // 1 = ML-KEM-768 + X25519 + AES-256-GCM + ML-DSA-65
    private val INFO = "MorseFinder sealed pack v1".toByteArray(Charsets.US_ASCII)

    class Identity(
        val kemPub: ByteArray, val kemPriv: ByteArray,
        val xPub: ByteArray, val xPriv: ByteArray,
        val dsaPub: ByteArray, val dsaPriv: ByteArray
    ) {
        fun publicBundle() = PublicBundle(kemPub, xPub, dsaPub)
    }

    class PublicBundle(val kemPub: ByteArray, val xPub: ByteArray, val dsaPub: ByteArray) {
        /** Short human-checkable fingerprint, e.g. "A1B2-C3D4-E5F6-0718-293A". */
        fun fingerprint(): String {
            val d = MessageDigest.getInstance("SHA-256").digest(kemPub + xPub + dsaPub)
            return d.take(10).joinToString("") { "%02X".format(it) }.chunked(4).joinToString("-")
        }
    }

    class Opened(val plain: ByteArray, val sender: PublicBundle)

    // ------------------------------------------------------------------ provider helpers
    private fun <T> anyProvider(block: (Provider) -> T): T {
        var last: Exception? = null
        for (p in listOf(bc, pqc)) {
            try { return block(p) } catch (e: Exception) { last = e }
        }
        throw IllegalStateException("post-quantum algorithms unavailable", last)
    }

    private fun <T> anyName(names: List<String>, block: (String) -> T): T {
        var last: Exception? = null
        for (n in names) {
            try { return block(n) } catch (e: Exception) { last = e }
        }
        throw last ?: IllegalStateException()
    }

    private val KEM_NAMES = listOf("ML-KEM-768", "ML-KEM")
    private val DSA_NAMES = listOf("ML-DSA-65", "ML-DSA")

    // ------------------------------------------------------------------ identity
    fun newIdentity(): Identity {
        val kem = anyProvider { p -> KeyPairGenerator.getInstance("ML-KEM-768", p).generateKeyPair() }
        val x = KeyPairGenerator.getInstance("X25519", bc).generateKeyPair()
        val dsa = anyProvider { p -> KeyPairGenerator.getInstance("ML-DSA-65", p).generateKeyPair() }
        return Identity(
            kem.public.encoded, kem.private.encoded,
            x.public.encoded, x.private.encoded,
            dsa.public.encoded, dsa.private.encoded
        )
    }

    fun encodeIdentity(id: Identity): ByteArray = packArrays(id.kemPub, id.kemPriv, id.xPub, id.xPriv, id.dsaPub, id.dsaPriv)

    fun decodeIdentity(b: ByteArray): Identity {
        val a = unpackArrays(b, 6)
        return Identity(a[0], a[1], a[2], a[3], a[4], a[5])
    }

    private val KEY_MAGIC = "MFK1".toByteArray(Charsets.US_ASCII)

    fun encodePublic(p: PublicBundle): ByteArray = KEY_MAGIC + packArrays(p.kemPub, p.xPub, p.dsaPub)

    fun isPublicKeyFile(b: ByteArray) = b.size > 4 && b.copyOfRange(0, 4).contentEquals(KEY_MAGIC)
    fun isSealedPack(b: ByteArray) = b.size > 4 && b.copyOfRange(0, 4).contentEquals(MAGIC)

    fun decodePublic(b: ByteArray): PublicBundle {
        require(isPublicKeyFile(b)) { "not a key file" }
        val a = unpackArrays(b.copyOfRange(4, b.size), 3)
        return PublicBundle(a[0], a[1], a[2])
    }

    // ------------------------------------------------------------------ seal / open
    fun seal(plain: ByteArray, to: PublicBundle, from: Identity): ByteArray {
        // 1. hybrid key establishment
        val (ssKem, ctKem) = encapsulate(to.kemPub)
        val eph = KeyPairGenerator.getInstance("X25519", bc).generateKeyPair()
        val ssX = x25519(eph.private.encoded, to.xPub)
        val ephPub = eph.public.encoded
        val key = deriveKey(ssKem, ssX, ctKem, ephPub, to)

        // 2. header (authenticated as AES-GCM AAD and again by the signature)
        val hdr = ByteArrayOutputStream()
        DataOutputStream(hdr).apply {
            write(MAGIC); writeByte(SUITE)
            writeShort(ctKem.size); write(ctKem)
            writeShort(ephPub.size); write(ephPub)
        }
        val header = hdr.toByteArray()
        val nonce = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        c.updateAAD(header)
        val ct = c.doFinal(plain)

        // 3. sender signature over header || nonce || ciphertext
        val body = ByteArrayOutputStream()
        DataOutputStream(body).apply {
            write(header); write(nonce); writeInt(ct.size); write(ct)
        }
        val signed = body.toByteArray()
        val sig = sign(signed, from.dsaPriv)
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            write(signed)
            writeShort(sig.size); write(sig)
            val me = from.publicBundle()
            writeShort(me.kemPub.size); write(me.kemPub)
            writeShort(me.xPub.size); write(me.xPub)
            writeShort(me.dsaPub.size); write(me.dsaPub)
        }
        return out.toByteArray()
    }

    fun open(blob: ByteArray, me: Identity): Opened {
        val inp = DataInputStream(ByteArrayInputStream(blob))
        val magic = ByteArray(4); inp.readFully(magic)
        require(magic.contentEquals(MAGIC)) { "bad magic" }
        require(inp.readUnsignedByte() == SUITE) { "unsupported suite" }
        val ctKem = ByteArray(inp.readUnsignedShort()).also { inp.readFully(it) }
        val ephPub = ByteArray(inp.readUnsignedShort()).also { inp.readFully(it) }
        val headerLen = 4 + 1 + 2 + ctKem.size + 2 + ephPub.size
        val nonce = ByteArray(12).also { inp.readFully(it) }
        val ctLen = inp.readInt()
        require(ctLen in 16..(64 * 1024 * 1024)) { "bad length" }
        val ct = ByteArray(ctLen).also { inp.readFully(it) }
        val signedLen = headerLen + 12 + 4 + ctLen
        val sig = ByteArray(inp.readUnsignedShort()).also { inp.readFully(it) }
        val sKem = ByteArray(inp.readUnsignedShort()).also { inp.readFully(it) }
        val sX = ByteArray(inp.readUnsignedShort()).also { inp.readFully(it) }
        val sDsa = ByteArray(inp.readUnsignedShort()).also { inp.readFully(it) }

        // verify signature FIRST (cheap reject of tampered / forged files)
        require(verify(blob.copyOfRange(0, signedLen), sig, sDsa)) { "signature invalid" }

        val ssKem = decapsulate(ctKem, me.kemPriv)
        val ssX = x25519(me.xPriv, ephPub)
        val key = deriveKey(ssKem, ssX, ctKem, ephPub, me.publicBundle())
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        c.updateAAD(blob.copyOfRange(0, headerLen))
        return Opened(c.doFinal(ct), PublicBundle(sKem, sX, sDsa))
    }

    // ------------------------------------------------------------------ primitives
    private fun encapsulate(kemPub: ByteArray): Pair<ByteArray, ByteArray> = anyProvider { p ->
        val pub = anyName(KEM_NAMES) { KeyFactory.getInstance(it, p).generatePublic(X509EncodedKeySpec(kemPub)) }
        val kg = anyName(listOf("ML-KEM", "ML-KEM-768")) { KeyGenerator.getInstance(it, p) }
        kg.init(KEMGenerateSpec(pub, "AES"), rnd)
        val sk = kg.generateKey() as SecretKeyWithEncapsulation
        sk.encoded to sk.encapsulation
    }

    private fun decapsulate(ct: ByteArray, kemPriv: ByteArray): ByteArray = anyProvider { p ->
        val priv = anyName(KEM_NAMES) { KeyFactory.getInstance(it, p).generatePrivate(PKCS8EncodedKeySpec(kemPriv)) }
        val kg = anyName(listOf("ML-KEM", "ML-KEM-768")) { KeyGenerator.getInstance(it, p) }
        kg.init(KEMExtractSpec(priv, ct, "AES"), rnd)
        (kg.generateKey() as SecretKeyWithEncapsulation).encoded
    }

    private fun x25519(privEnc: ByteArray, pubEnc: ByteArray): ByteArray {
        val kf = KeyFactory.getInstance("X25519", bc)
        val ka = KeyAgreement.getInstance("X25519", bc)
        ka.init(kf.generatePrivate(PKCS8EncodedKeySpec(privEnc)))
        ka.doPhase(kf.generatePublic(X509EncodedKeySpec(pubEnc)), true)
        return ka.generateSecret()
    }

    private fun sign(data: ByteArray, dsaPriv: ByteArray): ByteArray = anyProvider { p ->
        val priv = anyName(DSA_NAMES) { KeyFactory.getInstance(it, p).generatePrivate(PKCS8EncodedKeySpec(dsaPriv)) }
        val s = anyName(DSA_NAMES) { Signature.getInstance(it, p) }
        s.initSign(priv); s.update(data); s.sign()
    }

    private fun verify(data: ByteArray, sig: ByteArray, dsaPub: ByteArray): Boolean = try {
        anyProvider { p ->
            val pub = anyName(DSA_NAMES) { KeyFactory.getInstance(it, p).generatePublic(X509EncodedKeySpec(dsaPub)) }
            val s = anyName(DSA_NAMES) { Signature.getInstance(it, p) }
            s.initVerify(pub); s.update(data); s.verify(sig)
        }
    } catch (e: Exception) { false }

    /** HKDF-SHA256 (RFC 5869) over  ssKem || ssX25519, bound to the whole handshake transcript. */
    private fun deriveKey(ssKem: ByteArray, ssX: ByteArray, ctKem: ByteArray, ephPub: ByteArray, rcpt: PublicBundle): ByteArray {
        val ikm = ssKem + ssX
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(MAGIC, "HmacSHA256"))                 // salt
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(INFO); mac.update(ctKem); mac.update(ephPub)
        mac.update(MessageDigest.getInstance("SHA-256").digest(rcpt.kemPub + rcpt.xPub))
        mac.update(1.toByte())
        return mac.doFinal()                                         // 32 bytes = one HKDF block
    }

    // ------------------------------------------------------------------ tiny serialisation
    private fun packArrays(vararg a: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        DataOutputStream(o).apply { for (x in a) { writeInt(x.size); write(x) } }
        return o.toByteArray()
    }

    private fun unpackArrays(b: ByteArray, count: Int): List<ByteArray> {
        val i = DataInputStream(ByteArrayInputStream(b))
        return List(count) {
            val len = i.readInt()
            require(len in 0..(1 shl 20)) { "corrupt" }
            ByteArray(len).also { x -> i.readFully(x) }
        }
    }

    /** Round-trip self test used by the unit test / diagnostics. */
    fun selfTest(): Boolean = try {
        val a = newIdentity(); val b = newIdentity()
        val msg = "ok".toByteArray()
        val o = open(seal(msg, b.publicBundle(), a), b)
        o.plain.contentEquals(msg) && o.sender.fingerprint() == a.publicBundle().fingerprint()
    } catch (e: Exception) { false }
}
