/*-
 * #%L
 * Nessus DIDComm :: Agent
 * %%
 * Copyright (C) 2022 - 2023 Nessus
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
package org.nessus.didcomm.service

import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import id.walt.common.prettyPrint
import id.walt.servicematrix.ServiceProvider
import id.walt.services.crypto.CryptoService
import id.walt.services.keystore.KeyStoreService
import id.walt.services.keystore.KeyType
import mu.KotlinLogging
import org.nessus.didcomm.did.Did
import org.nessus.didcomm.util.*
import java.util.*


class DidDocumentService: NessusBaseService() {
    override val implementation get() = serviceImplementation<DidService>()
    override val log = KotlinLogging.logger {}

    companion object: ServiceProvider {
        private val implementation = DidDocumentService()
        override fun getService() = implementation
    }

    private val cryptoService get() = CryptoService.getService()
    private val didService get() = DidService.getService()
    private val keyStore get() = KeyStoreService.getService()

    fun createDidDocument(did: Did, endpointUrl: String): RFC0023DidDocument {

        val template = """
        {
            "@context": "https://w3id.org/did/v1",
            "id": "${did.qualified}",
            "publicKey": [
                {
                    "id": "${did.qualified}#1",
                    "type": "Ed25519VerificationKey2018",
                    "controller": "${did.qualified}",
                    "publicKeyBase58": "${did.verkey}"
                }
            ],
            "authentication": [
                {
                    "type": "Ed25519SignatureAuthentication2018",
                    "publicKey": "${did.qualified}#1"
                }
            ],
            "service": [
                {
                    "id": "${did.qualified};srv",
                    "type": "NessusAgent",
                    "priority": 0,
                    "recipientKeys": [
                        "${did.verkey}"
                    ],
                    "serviceEndpoint": "$endpointUrl"
                }
            ]
        }
        """.trimJson()
        return gson.fromJson(template, RFC0023DidDocument::class.java)
    }

    fun createAttachment(diddocJson: String, sigDid: Did): JsonObject {
        val didDoc = gson.fromJson(diddocJson, RFC0023DidDocument::class.java)
        return createAttachment(didDoc, sigDid)
    }

    fun createAttachment(didDocument: RFC0023DidDocument, sigDid: Did): JsonObject {

        val didDocumentJson = gson.toJson(didDocument)
        val didDocument64 = didDocumentJson.toByteArray().encodeBase64Url()

        val octetKeyPair = sigDid.toOctetKeyPair()
        val didKey = keyStore.load(sigDid.verkey, KeyType.PUBLIC).toDidKey()

        val protectedTemplate = """
        {
            "alg": "${octetKeyPair.algorithm}",
            "kid": "${didKey.qualified}",
            "jwk": {
                "kty": "${octetKeyPair.keyType}",
                "crv": "${octetKeyPair.curve}",
                "x": "${octetKeyPair.x}",
                "kid": "${didKey.qualified}"
            }
        }            
        """.trimJson()

        val protected64 = protectedTemplate.toByteArray().encodeBase64Url()

        val data = "$protected64.$didDocument64".toByteArray()
        val keyId = keyStore.load(sigDid.verkey).keyId
        val signature64 = cryptoService.sign(keyId, data).encodeBase64Url()

        val template = """
        {
            "@id": "${UUID.randomUUID()}",
            "mime-type": "application/json",
            "data": {
              "base64": "$didDocument64",
              "jws": {
                "header": {
                  "kid": "${didKey.qualified}"
                },
                "protected": "$protected64",
                "signature": "$signature64"
              }
            }
          }
        """.trimJson()

        return gson.fromJson(template, JsonObject::class.java)!!
    }

    fun extractFromAttachment(attachment: String, verkey: String?): RFC0023DidDocumentAttachment {

        val didDocument64 = attachment.selectJson("data.base64")
        val jwsProtected64 = attachment.selectJson("data.jws.protected")
        val jwsSignature64 = attachment.selectJson("data.jws.signature")
        val jwsHeaderKid = attachment.selectJson("data.jws.header.kid")
        checkNotNull(didDocument64) { "No 'data.base64'" }
        checkNotNull(jwsProtected64) { "No 'data.jws.protected'" }
        checkNotNull(jwsSignature64) { "No 'data.jws.signature'" }
        checkNotNull(jwsHeaderKid) { "No 'data.jws.header.kid'" }

        val diddocJson = didDocument64.decodeBase64UrlStr() // Contains json whitespace
        val didDocument = gson.fromJson(diddocJson, RFC0023DidDocument::class.java)

        val signature = jwsSignature64.decodeBase64Url()
        val data = "$jwsProtected64.$didDocument64".toByteArray()
        log.info { "Extracted Did Document: ${diddocJson.prettyPrint()}" }

        // Verify that all verkeys in the publicKey section
        // are also listed in service.recipientKeys
        val recipientKeys = didDocument.service[0].recipientKeys
        didDocument.publicKey.forEach {
            check(recipientKeys.contains(it.publicKeyBase58))
        }

        val jwsHeaderDid = Did.fromSpec(jwsHeaderKid)
        val publicKeyDid = didDocument.publicKeyDid()

        val signatoryDid = if (verkey != null) {
            val key = keyStore.load(verkey, KeyType.PUBLIC)
            check(cryptoService.verify(key.keyId, signature, data)) { "Did Document signature verification failed with: $verkey" }
            key.toDidKey()
        } else {
            // The JWS header.kid is expected to be the did:key
            // representation of the DidDocument's public key
            check(jwsHeaderDid.verkey == publicKeyDid.verkey) { "Verkey mismatch" }

            // The signatoryDid is already registered when the DidEx Request
            // received here was also created by this agent instance
            if (keyStore.getKeyId(jwsHeaderDid.verkey) == null) {
                didService.registerWithKeyStore(jwsHeaderDid)
            }

            val keyId = keyStore.load(jwsHeaderDid.verkey, KeyType.PUBLIC).keyId
            check(cryptoService.verify(keyId, signature, data)) { "Did Document signature verification failed with: ${jwsHeaderDid.qualified}" }
            jwsHeaderDid
        }

        return RFC0023DidDocumentAttachment(didDocument, signatoryDid)
    }
}

data class RFC0023DidDocumentAttachment(
    val didDocument: RFC0023DidDocument,
    val signatoryDid: Did
)

data class RFC0023DidDocument(
    @SerializedName("@context")
    val atContext: String,
    val id: String,
    val publicKey: List<PublicKey>,
    val authentication: List<Authentication>,
    val service: List<Service>,
) {

    fun publicKeyDid(idx: Int = 0): Did {
        check(publicKey.size > idx) { "No publicKey[$idx]" }
        val didSpec = publicKey[idx].controller as? String
        val didVerkey = publicKey[idx].publicKeyBase58 as? String
        checkNotNull(didSpec) { "No 'publicKey[$idx].controller'" }
        checkNotNull(didVerkey) { "No 'publicKey[$idx].publicKeyBase58'" }
        return Did.fromSpec(didSpec, didVerkey)
    }

    fun serviceEndpoint(idx: Int = 0): String {
        check(service.size > idx) { "No service[$idx]" }
        return service[idx].serviceEndpoint
    }

    data class PublicKey(
        val id: String,
        val type: String,
        val controller: String,
        val publicKeyBase58: String)

    data class Authentication(
        val type: String,
        val publicKey: String)

    data class Service(
        val id: String,
        val type: String,
        val priority: Int,
        val recipientKeys: List<String>,
        val serviceEndpoint: String)
}
