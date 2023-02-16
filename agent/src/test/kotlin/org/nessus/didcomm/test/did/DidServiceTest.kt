/*-
 * #%L
 * Nessus DIDComm :: Core
 * %%
 * Copyright (C) 2022 Nessus
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package org.nessus.didcomm.test.did

import com.goterl.lazysodium.interfaces.Sign
import com.goterl.lazysodium.utils.KeyPair
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.OctetKeyPair
import id.walt.crypto.Key
import id.walt.crypto.KeyAlgorithm
import id.walt.crypto.buildEd25519PubKey
import id.walt.crypto.convertRawKeyToMultiBase58Btc
import id.walt.crypto.decodeBase58
import id.walt.crypto.encBase64
import id.walt.crypto.getMulticodecKeyCode
import id.walt.services.CryptoProvider
import id.walt.services.key.Keys
import id.walt.services.keystore.KeyStoreService
import id.walt.services.keystore.KeyType
import io.kotest.matchers.shouldBe
import mu.KotlinLogging
import org.nessus.didcomm.crypto.LazySodiumService.lazySodium
import org.nessus.didcomm.did.DidMethod
import org.nessus.didcomm.service.toOctetKeyPair
import org.nessus.didcomm.test.AbstractAgentTest
import org.nessus.didcomm.test.Alice
import org.nessus.didcomm.test.Faber
import org.nessus.didcomm.util.decodeHex
import org.nessus.didcomm.util.encodeBase64Url
import org.nessus.didcomm.util.encodeHex
import org.nessus.didcomm.util.encodeJson
import org.nessus.didcomm.util.trimJson
import java.security.PublicKey

/**
 * Ed25519 Online Tool - Sign or Verify
 * https://cyphr.me/ed25519_tool/ed.html
 *
 * did:key test vectors
 * https://w3c-ccg.github.io/did-method-key/#test-vectors
 * https://github.com/w3c-ccg/did-method-key/tree/main/test-vectors
 */
class DidServiceTest: AbstractAgentTest() {
    val log = KotlinLogging.logger {}

    @Test
    fun test_RawPubKey_to_DidKey() {

        // ed25519-x25519.json
        // did:key:z6MkiTBz1ymuepAQ4HEHYSF1H8quG5GLVVQR3djdX3mDooWp

        val pkRaw = "4zvwRjXUKGfvwnParsHAS3HuSVzV5cA4McphgmoCtajS".decodeBase58()
        log.info { "pkRaw: ${pkRaw.encodeHex()}" }

        // Build PublicKey from pubkey raw bytes
        // Then verify that we can get the raw bytes from the X.509 encoded PublicKey
        val pubKey: PublicKey = buildEd25519PubKey(encBase64(pkRaw))
        pubKey.format shouldBe "X.509"
        val pubKeyX509 = pubKey.encoded
        log.info { "pk509: ${pubKeyX509.encodeHex()}" }

        // We assume/verify that the last 32 bytes are equal to the pubkey raw bytes
        val pubKeyRaw = pubKeyX509.sliceArray(pubKeyX509.size - 32 until pubKeyX509.size)
        log.info { "pkRaw: ${pubKeyRaw.encodeHex()}" }
        pkRaw.contentEquals(pubKeyRaw) shouldBe true

        // Construct did from the 32 pubkey raw bytes
        val keyAlgorithm = KeyAlgorithm.EdDSA_Ed25519
        check(pubKeyRaw.size == 32) { "Expect 32 pubkey bytes" }
        val did = convertRawKeyToMultiBase58Btc(pubKeyRaw, getMulticodecKeyCode(keyAlgorithm))
        "did:key:$did" shouldBe "did:key:z6MkiTBz1ymuepAQ4HEHYSF1H8quG5GLVVQR3djdX3mDooWp"
    }

    @Test
    fun test_DidKey_Seed00() {
        val seedBytes = ByteArray(32)
        val did = didService.createDid(DidMethod.KEY, seed=seedBytes)
        did.qualified shouldBe "did:key:z6MkiTBz1ymuepAQ4HEHYSF1H8quG5GLVVQR3djdX3mDooWp"
    }

    @Test
    fun test_OKP_Ed25519_X25519() {

        val seedBytes = "0000000000000000000000000000000000000000000000000000000000000005".decodeHex()
        val didKey05 = didService.createDid(DidMethod.KEY, seed=seedBytes)
        didKey05.qualified shouldBe "did:key:z6MkwYMhwTvsq376YBAcJHy3vyRWzBgn5vKfVqqDCgm7XVKU"

        // Test OKP Ed25519
        run {

            // Load the key associated with the DID and get the OKP representation of it
            val key: Key = keyStore.load(didKey05.qualified, KeyType.PRIVATE)
            val octetKeyPair: OctetKeyPair = key.toOctetKeyPair()
            log.info { octetKeyPair.toJSONObject().encodeJson(true) }

            key.cryptoProvider shouldBe CryptoProvider.SUN
            key.algorithm shouldBe KeyAlgorithm.EdDSA_Ed25519
            octetKeyPair.curve.name shouldBe "Ed25519"

            // Get the public/private key bytes
            val keys = Keys(key.keyId.id, key.keyPair!!, "SunEC")
            val ed25519PubBytes = keys.getPubKey()
            val ed25519PrvBytes = keys.getPrivKey()
            log.info { "ed25519PubBytes: ${ed25519PubBytes.encodeHex()}" }
            log.info { "ed25519PrvBytes: ${ed25519PrvBytes.encodeHex()}" }

            val x = ed25519PubBytes.encodeBase64Url()
            val d = ed25519PrvBytes.encodeBase64Url()
            log.info { "x: $x" }
            log.info { "d: $d" }

            // Assert that we curve coordinates match
            x shouldBe "${octetKeyPair.x}"
            d shouldBe "${octetKeyPair.d}"
            x shouldBe "_eT7oDCtAC98L31MMx9J0T-w7HR-zuvsY08f9MvKne8"
            d shouldBe "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAU"
        }

        // Test OKP X25519
        run {
            // Load the key associated with the DID and get the OKP representation of it
            val key: Key = keyStore.load(didKey05.qualified, KeyType.PRIVATE)

            key.cryptoProvider shouldBe CryptoProvider.SUN
            key.algorithm shouldBe KeyAlgorithm.EdDSA_Ed25519

            // Get the public/private key bytes
            val keys = Keys(key.keyId.id, key.keyPair!!, "SunEC")
            val ed25519PubBytes = keys.getPubKey()
            val ed25519PrvBytes = keys.getPrivKey()
            log.info { "ed25519PubBytes: ${ed25519PubBytes.encodeHex()}" }
            log.info { "ed25519PrvBytes: ${ed25519PrvBytes.encodeHex()}" }

            val ed25519KeyPair = KeyPair(
                com.goterl.lazysodium.utils.Key.fromBytes(ed25519PubBytes),
                com.goterl.lazysodium.utils.Key.fromBytes(ed25519PrvBytes))

            val lazySign = lazySodium as Sign.Lazy
            val x25519KeyPair = lazySign.convertKeyPairEd25519ToCurve25519(ed25519KeyPair)
            val x25519PubBytes = x25519KeyPair.publicKey.asBytes
            val x25519PrvBytes = x25519KeyPair.secretKey.asBytes

            log.info { "x25519PubBytes: ${x25519PubBytes.encodeHex()}" }
            log.info { "x25519PrvBytes: ${x25519PrvBytes.encodeHex()}" }

            val x = x25519PubBytes.encodeBase64Url()
            val d = x25519PrvBytes.encodeBase64Url()
            log.info { "x: $x" }
            log.info { "d: $d" }

            val octetKeyPair = OctetKeyPair.parse("""
            {
                "kty": "OKP",
                "crv": "X25519",
                "x": "$x",
                "d": "$d"
            }                
            """.trimJson())

            // Assert that we curve coordinates match
            x shouldBe "jRIz3oriXDNZmnb35XQb7K1UIlz3ae1ao1YSqLeBXHs"
            d shouldBe "aEAAB3VBFPCQtgF3N__wRiXhMOgeiRGstpPC3gnJ1Eo"
        }

        // Do the above through the DidService
        run {

            val ed25519Prv = didService.toOctetKeyPair(didKey05.verkey, Curve.Ed25519, KeyType.PRIVATE)
            "${ed25519Prv.x}" shouldBe "_eT7oDCtAC98L31MMx9J0T-w7HR-zuvsY08f9MvKne8"
            "${ed25519Prv.d}" shouldBe "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAU"

            val x25519Prv = didService.toOctetKeyPair(didKey05.verkey, Curve.X25519, KeyType.PRIVATE)
            "${x25519Prv.x}" shouldBe "jRIz3oriXDNZmnb35XQb7K1UIlz3ae1ao1YSqLeBXHs"
            "${x25519Prv.d}" shouldBe "aEAAB3VBFPCQtgF3N__wRiXhMOgeiRGstpPC3gnJ1Eo"

            val ed25519Pub = didService.toOctetKeyPair(didKey05.verkey, Curve.Ed25519)
            "${ed25519Pub.x}" shouldBe "_eT7oDCtAC98L31MMx9J0T-w7HR-zuvsY08f9MvKne8"
            ed25519Pub.d shouldBe null

            val x25519Pub = didService.toOctetKeyPair(didKey05.verkey, Curve.X25519)
            "${x25519Pub.x}" shouldBe "jRIz3oriXDNZmnb35XQb7K1UIlz3ae1ao1YSqLeBXHs"
            x25519Pub.d shouldBe null
        }
    }

    @Test
    fun test_Did_Fixture() {

        val keyStore = KeyStoreService.getService()

        val faberKey = didService.createDid(DidMethod.KEY, seed=Faber.seed.toByteArray())
        val key = keyStore.load(faberKey.qualified, KeyType.PRIVATE)

        val pubKey = key.keyPair?.public
        val prvKey = key.keyPair?.private
        val pubkeyBytes = pubKey?.encoded
        val prvkeyBytes = prvKey?.encoded
        val verkey58 = faberKey.verkey
        val verkeyBytes = verkey58.decodeBase58()
        log.info { faberKey.qualified }
        log.info { "seed:      ${Faber.seed}" }
        log.info { "verkey58:  ${faberKey.verkey}" }
        log.info { "verkeyHex: ${verkeyBytes.encodeHex()}" }
        log.info { "seedHex:   ${Faber.seed.toByteArray().encodeHex()}" }
        log.info { "pubkeyHex: ${pubKey?.format} ${pubkeyBytes?.encodeHex()}" }
        log.info { "prvkeyHex: ${prvKey?.format} ${prvkeyBytes?.encodeHex()}" }
        faberKey.verkey shouldBe Faber.verkey
        faberKey.qualified shouldBe Faber.didkey

        val faberSov = didService.createDid(DidMethod.SOV, seed=Faber.seed.toByteArray())
        faberSov.verkey shouldBe Faber.verkey
        faberSov.qualified shouldBe Faber.didsov

        // Alice -------------------------------------------------------------------------------------------------------

        val aliceKey = didService.createDid(DidMethod.KEY, seed=Alice.seed.toByteArray())
        aliceKey.verkey shouldBe Alice.verkey
        aliceKey.qualified shouldBe Alice.didkey

        val aliceSov = didService.createDid(DidMethod.SOV, seed=Alice.seed.toByteArray())
        aliceSov.verkey shouldBe Alice.verkey
        aliceSov.qualified shouldBe Alice.didsov
    }
}
