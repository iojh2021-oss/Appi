package com.morsefinder.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PqCryptoTest {
    @Test fun selfTestPasses() { assertTrue(PqCrypto.selfTest()) }

    @Test fun roundTripAndSenderFingerprint() {
        val alice = PqCrypto.newIdentity(); val bob = PqCrypto.newIdentity()
        val msg = "{\"A\":[[0.1,0.2]]}".toByteArray()
        val sealed = PqCrypto.seal(msg, bob.publicBundle(), alice)
        assertTrue(PqCrypto.isSealedPack(sealed))
        val o = PqCrypto.open(sealed, bob)
        assertArrayEquals(msg, o.plain)
        assertEquals(alice.publicBundle().fingerprint(), o.sender.fingerprint())
    }

    @Test fun tamperedCiphertextIsRejected() {
        val a = PqCrypto.newIdentity(); val b = PqCrypto.newIdentity()
        val s = PqCrypto.seal("secret".toByteArray(), b.publicBundle(), a)
        s[s.size / 3] = (s[s.size / 3].toInt() xor 1).toByte()
        try { PqCrypto.open(s, b); fail("must not open") } catch (e: Exception) { /* expected */ }
    }

    @Test fun wrongRecipientCannotOpen() {
        val a = PqCrypto.newIdentity(); val b = PqCrypto.newIdentity(); val c = PqCrypto.newIdentity()
        val s = PqCrypto.seal("secret".toByteArray(), b.publicBundle(), a)
        try { PqCrypto.open(s, c); fail("must not open") } catch (e: Exception) { /* expected */ }
    }

    @Test fun keyFileRoundTrip() {
        val a = PqCrypto.newIdentity()
        val enc = PqCrypto.encodePublic(a.publicBundle())
        assertTrue(PqCrypto.isPublicKeyFile(enc))
        assertEquals(a.publicBundle().fingerprint(), PqCrypto.decodePublic(enc).fingerprint())
        val id2 = PqCrypto.decodeIdentity(PqCrypto.encodeIdentity(a))
        assertEquals(a.publicBundle().fingerprint(), id2.publicBundle().fingerprint())
    }
}
