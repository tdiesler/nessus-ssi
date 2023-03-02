package org.nessus.didcomm.test.vc

import com.danubetech.verifiablecredentials.VerifiableCredential
import com.danubetech.verifiablecredentials.validation.Validation
import info.weboftrust.ldsignatures.verifier.RsaSignature2018LdVerifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.nessus.didcomm.test.AbstractAgentTest

class VerifyCredentialTest: AbstractAgentTest() {

    @Test
    fun testVerify() {

        val verifiableCredential = VerifiableCredential.fromJson(
            readResource("/example/vc/signed.good.vc.jsonld"))

        Validation.validateJsonLd(verifiableCredential)
        val verifier = RsaSignature2018LdVerifier(SignatureKeys.testRSAPublicKey)
        val verify = verifier.verify(verifiableCredential)
        assertTrue(verify)

        val credentialSubject = verifiableCredential.credentialSubject
        val givenName = if (credentialSubject == null) null else credentialSubject.claims["givenName"] as String?
        assertEquals("Manu", givenName)
    }

    @Test
    fun testBadVerify() {

        val verifiableCredential = VerifiableCredential.fromJson(
            readResource("/example/vc/signed.bad.vc.jsonld"))

        Validation.validateJsonLd(verifiableCredential)

        val verifier = RsaSignature2018LdVerifier(SignatureKeys.testRSAPublicKey)
        val verify = verifier.verify(verifiableCredential)
        assertFalse(verify)
    }
}