package org.nessus.didcomm.service

import id.walt.services.vc.JsonLdCredentialService
import id.walt.services.vc.JwtCredentialService
import mu.KotlinLogging
import org.nessus.didcomm.model.W3CVerifiableCredential
import org.nessus.didcomm.util.trimJson
import java.time.Instant

object NessusCustodianService: ObjectService<NessusCustodianService>() {
    val log = KotlinLogging.logger {}

    override fun getService() = apply { }

    private val jwtCredentialService = JwtCredentialService.getService()
    private val jsonLdCredentialService = JsonLdCredentialService.getService()

    fun createPresentation(
        vcs: Array<W3CVerifiableCredential>,
        holderDid: String,
        verifierDid: String? = null,
        domain: String? = null,
        challenge: String? = null,
        expirationDate: Instant? = null
    ): W3CVerifiableCredential {
        return createPresentation(vcs.map { it.encodeJson() }, holderDid, verifierDid, domain, challenge, expirationDate)
    }

    fun createPresentation(
        vcs: List<String>,
        holderDid: String,
        verifierDid: String? = null,
        domain: String? = null,
        challenge: String? = null,
        expirationDate: Instant? = null
    ): W3CVerifiableCredential {

        val vpJson = when {
            vcs.stream().allMatch { W3CVerifiableCredential.isJWT(it) } -> jwtCredentialService.present(
                vcs,
                holderDid,
                verifierDid,
                challenge,
                expirationDate
            )

            vcs.stream().noneMatch { W3CVerifiableCredential.isJWT(it) } -> jsonLdCredentialService.present(
                vcs,
                holderDid,
                domain,
                challenge,
                expirationDate
            )

            else -> throw IllegalStateException("All verifiable credentials must be of the same proof type.")
        }

        return W3CVerifiableCredential.fromJson(vpJson.trimJson())
    }
}