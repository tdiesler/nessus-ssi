package org.nessus.didcomm.protocol

import id.walt.common.prettyPrint
import org.hyperledger.aries.api.did_exchange.DidExchangeAcceptInvitationFilter
import org.nessus.didcomm.agent.AriesClient
import org.nessus.didcomm.model.Connection
import org.nessus.didcomm.model.ConnectionState
import org.nessus.didcomm.model.ConnectionState.REQUEST
import org.nessus.didcomm.model.Invitation
import org.nessus.didcomm.protocol.RFC0019EncryptionEnvelope.Companion.RFC0019_ENCRYPTED_ENVELOPE_MEDIA_TYPE
import org.nessus.didcomm.protocol.RFC0434OutOfBandProtocol.Companion.INVITEE_WALLET_ATTACHMENT
import org.nessus.didcomm.service.RFC0023DidDocument
import org.nessus.didcomm.service.RFC0023_DIDEXCHANGE
import org.nessus.didcomm.util.AttachmentKey
import org.nessus.didcomm.util.decodeBase64Str
import org.nessus.didcomm.util.gson
import org.nessus.didcomm.util.selectJson
import org.nessus.didcomm.util.trimJson
import org.nessus.didcomm.wallet.AgentType
import org.nessus.didcomm.wallet.DidMethod
import org.nessus.didcomm.wallet.Wallet
import org.nessus.didcomm.wallet.toWalletModel
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Aries RFC 0023: DID Exchange Protocol 1.0
 * https://github.com/hyperledger/aries-rfcs/tree/main/features/0023-did-exchange
 */
class RFC0023DidExchangeProtocol(mex: MessageExchange): Protocol<RFC0023DidExchangeProtocol>(mex) {

    override val protocolUri = RFC0023_DIDEXCHANGE.uri

    companion object {
        val RFC0023_DIDEXCHANGE_MESSAGE_TYPE_REQUEST = "${RFC0023_DIDEXCHANGE.uri}/request"
        val RFC0023_DIDEXCHANGE_MESSAGE_TYPE_RESPONSE = "${RFC0023_DIDEXCHANGE.uri}/response"
        val RFC0023_DIDEXCHANGE_MESSAGE_TYPE_COMPLETE = "${RFC0023_DIDEXCHANGE.uri}/complete"
    }

    override fun invokeMethod(to: Wallet, messageType: String): Boolean {
        when (messageType) {
            RFC0023_DIDEXCHANGE_MESSAGE_TYPE_RESPONSE -> receiveDidExchangeResponse(to)
            else -> throw IllegalStateException("Unsupported message type: $messageType")
        }
        return true
    }

    fun sendDidExchangeRequest(): RFC0023DidExchangeProtocol {

        val requester = mex.getAttachment(INVITEE_WALLET_ATTACHMENT)
        checkNotNull(requester)  { "No requester attachment" }

        val invId = mex.last.thid as String
        val invitation = requester.getInvitation(invId)
        checkNotNull(invitation) { "No invitation with id: $invId" }

        val didexRequest = if (requester.agentType == AgentType.ACAPY) {
            sendDidExchangeRequestAcapy(requester, invitation)
        } else {
            sendDidExchangeRequestNessus(requester, invitation)
        }

        check(didexRequest.messageId == didexRequest.thid) { "Unexpected thread id: $didexRequest" }
        mex.addMessage(didexRequest)

        val conId = didexRequest.messageId as String
        val pcon = requester.getConnection(conId)
        checkNotNull(pcon) { "No peer connection" }

        // Attach the Connection
        val pconKey = AttachmentKey(Connection::class.java)
        mex.putAttachment(pconKey, pcon)

        // Register the response future with the message exchange
        val futureId = "$RFC0023_DIDEXCHANGE_MESSAGE_TYPE_RESPONSE/${didexRequest.thid}"
        val futureKey = AttachmentKey(futureId, CompletableFuture::class.java)
        mex.putAttachment(futureKey, CompletableFuture<EndpointMessage>())

        val packedDidExRequest = RFC0019EncryptionEnvelope()
            .packEncryptedEnvelope(didexRequest.bodyAsJson, pcon.myDid, pcon.theirDid)

        val packedEpm = EndpointMessage(packedDidExRequest, mapOf(
            "Content-Type" to RFC0019_ENCRYPTED_ENVELOPE_MEDIA_TYPE
        ))

        dispatchToEndpoint(pcon.theirEndpointUrl, packedEpm)
        return this
    }

    fun awaitDidExchangeResponse(timeout: Int, unit: TimeUnit): RFC0023DidExchangeProtocol {
        val didexThreadId = mex.last.thid as String
        val futureId = "$RFC0023_DIDEXCHANGE_MESSAGE_TYPE_RESPONSE/$didexThreadId"
        val futureKey = AttachmentKey(futureId, CompletableFuture::class.java)
        val future = mex.getAttachment(futureKey)
        if (future != null) {
            log.info {"Wait on future: $futureKey"}
            future.get(timeout.toLong(), unit)
        }
        return this
    }

    @Suppress("UNCHECKED_CAST")
    fun receiveDidExchangeResponse(requester: Wallet): RFC0023DidExchangeProtocol {

        val didexThreadId = mex.last.thid as String
        val futureId = "$RFC0023_DIDEXCHANGE_MESSAGE_TYPE_RESPONSE/$didexThreadId"
        val futureKey = AttachmentKey(futureId, CompletableFuture::class.java)
        val future = mex.removeAttachment(futureKey) as? CompletableFuture<EndpointMessage>
        if (future != null) {
            log.info {"Complete future: $futureKey"}
            future.complete(mex.last)
        }
        return this
    }

    fun sendDidExchangeComplete(): RFC0023DidExchangeProtocol {

        val requester = mex.getAttachment(INVITEE_WALLET_ATTACHMENT)
        checkNotNull(requester)  { "No requester attachment" }

        val pcon = mex.getConnection()
        checkNotNull(pcon) { "No peer connection" }

        val invId = mex.last.pthid as String
        val invitation = requester.getInvitation(invId)
        checkNotNull(invitation) { "No invitation with id: $invId" }

        val didexComplete = receiveDidExResponseMessage(requester, mex.last)
        val theirServiceEndpoint = invitation.getRecipientServiceEndpoint()

        val packedDidExComplete = RFC0019EncryptionEnvelope()
            .packEncryptedEnvelope(didexComplete.bodyAsJson, pcon.myDid, pcon.theirDid)

        val packedEpm = EndpointMessage(packedDidExComplete, mapOf(
            "Content-Type" to RFC0019_ENCRYPTED_ENVELOPE_MEDIA_TYPE
        ))

        dispatchToEndpoint(theirServiceEndpoint, packedEpm)
        pcon.state = ConnectionState.COMPLETED
        return this
    }

    /**
     * Create a DidExchange Request
     */

    /**
     * Receive a DidExchange Request
     * https://github.com/hyperledger/aries-rfcs/tree/main/features/0023-did-exchange#1-exchange-request
     *
     * - The @type attribute is a required string value that denotes that the received message is an exchange request
     * - The ~thread decorator MUST be included:
     *      - It MUST include the ID of the parent thread (pthid) such that the request can be correlated to the corresponding (implicit or explicit) invitation.
     *      - It MAY include the thid property. If thid is not defined it is implicitly defined as @id.
     * - The label attribute provides a suggested label for the DID being exchanged.
     * - The goal_code (optional) is a self-attested code the receiver may want to display to the user or use in automatically deciding what to do with the request message.
     * - The goal (optional) is a self-attested string that the receiver may want to display to the user about the context-specific goal of the request message.
     * - The did attribute MUST be included. It indicates the DID being exchanged.
     * - The did_doc~attach (optional), contains the DIDDoc associated with the did, if required.
     *      - If the did is resolvable (either an inline peer:did or a publicly resolvable DID), the did_doc~attach attribute should not be included.
     *      - If the DID is a did:peer DID, the DIDDoc must be as outlined in RFC 0627 Static Peer DIDs.
     */
    fun receiveRequest(responder: Wallet, epm: EndpointMessage) {

        val body = epm.bodyAsJson

        val pthid = epm.pthid
        checkNotNull(pthid) { "Must include the ID of the parent thread" }

        /**
         * Correlating requests to invitations
         *
         * An invitation is presented in one of two forms:
         *  - An explicit out-of-band invitation with its own @id.
         *  - An implicit invitation contained in a DID document's service attribute that conforms to the DIDComm conventions.
         */
        val invitation = responder.getInvitation(pthid)
        checkNotNull(invitation) { "Cannot find invitation for: $pthid" }

        /**
         * Request processing
         *
         * After receiving the exchange request, the responder evaluates the provided DID and DID Doc according to the DID Method Spec.
         * The responder should check the information presented with the keys used in the wire-level message transmission to ensure they match.
         * The responder MAY look up the corresponding invitation identified in the request's ~thread.pthid to determine whether it should accept this exchange request.
         * If the responder wishes to continue the exchange, they will persist the received information in their wallet. They will then either update the provisional service information to rotate the key, or provision a new DID entirely. The choice here will depend on the nature of the DID used in the invitation.
         * The responder will then craft an exchange response using the newly updated or provisioned information.
         */

        val didDocAttach64 = body.selectJson("did_doc~attach.data.base64") as? String
        checkNotNull(didDocAttach64) {"Cannot find attached did document"}
        val didDocAttach = didDocAttach64.decodeBase64Str()
        log.info { "Attached Did Document: ${didDocAttach.prettyPrint()}" }

        val rfc0023DidDoc = gson.fromJson(didDocAttach, RFC0023DidDocument::class.java)
        check(rfc0023DidDoc.atContext == "https://w3id.org/did/v1") { "Unexpected @context: ${rfc0023DidDoc.atContext}" }

        val protected64 = body.selectJson("did_doc~attach.data.jws.protected") as? String
        val protected = protected64?.decodeBase64Str()
        log.info { "JWS protected: ${protected?.prettyPrint()}" }

        // Complete the threadId future waiting for this message
        // messageExchange.completeThreadIdFuture(invitation.atId, rfc0023DidDoc)
    }

    fun receiveDidExResponseMessage(requester: Wallet, epm: EndpointMessage): EndpointMessage {
        val didexComplete = """
        {
            "@type": "$RFC0023_DIDEXCHANGE_MESSAGE_TYPE_COMPLETE",
            "@id": "${UUID.randomUUID()}",
            "~thread": {
                "thid": "${epm.thid}",
                "pthid": "${epm.pthid}"
            }
        }
        """.trimJson()

        return EndpointMessage(didexComplete)
    }

    // Private ---------------------------------------------------------------------------------------------------------

    private fun sendDidExchangeRequestAcapy(requester: Wallet, invitation: Invitation): EndpointMessage {

        val acceptInvitationFilter = DidExchangeAcceptInvitationFilter()
        acceptInvitationFilter.myEndpoint = requester.endpointUrl
        acceptInvitationFilter.myLabel = "Accept Invitation"

        val requesterClient = requester.walletClient() as AriesClient
//        responderClient.didExchangeAcceptInvitation(requesterConnectionId, acceptInvitationFilter).get()
//
//        // Expect invitee connection in state 'active'
//        messageExchange.awaitConnectionState(invitee, setOf(ConnectionState.ACTIVE))

        return EndpointMessage("")
    }

    private fun sendDidExchangeRequestNessus(requester: Wallet, invitation: Invitation): EndpointMessage {

        val responderDid = invitation.getRecipientDid()
        val inviterServiceEndpoint = invitation.getRecipientServiceEndpoint()

        // Create the Invitee Did & Document
        val requesterDid = requester.createDid(DidMethod.SOV)
        val requesterDidDoc = """
        {
            "@context": "https://w3id.org/did/v1",
            "id": "${requesterDid.qualified}",
            "publicKey": [
                {
                    "id": "${requesterDid.qualified}#1",
                    "type": "Ed25519VerificationKey2018",
                    "controller": "${requesterDid.qualified}",
                    "publicKeyBase58": "${requesterDid.verkey}"
                }
            ],
            "authentication": [
                {
                    "type": "Ed25519SignatureAuthentication2018",
                    "publicKey": "${requesterDid.qualified}#1"
                }
            ],
            "service": [
                {
                    "id": "${requesterDid.qualified};memory",
                    "type": "NessusAgent",
                    "priority": 0,
                    "recipientKeys": [
                        "${requesterDid.verkey}"
                    ],
                    "serviceEndpoint": "${requester.endpointUrl}"
                }
            ]
        }                    
        """.trimJson()
        log.info { "Invitee's Did Document: ${requesterDidDoc.prettyPrint()}" }

        val requesterDidDocAttach = diddocService.createAttachment(requesterDidDoc, requesterDid)

        val didexRequestId = "${UUID.randomUUID()}"
        val didexRequest = """
        {
            "@type": "$RFC0023_DIDEXCHANGE_MESSAGE_TYPE_REQUEST",
            "@id": "$didexRequestId",
            "~thread": {
                "thid": "$didexRequestId",
                "pthid": "${invitation.id}"
            },
            "label": "Accept Faber/Alice",
            "did": "${requesterDid.id}",
            "did_doc~attach": $requesterDidDocAttach
        }
        """.trimJson()

        val con = Connection(didexRequestId, requesterDid, responderDid, inviterServiceEndpoint, REQUEST)
        requester.toWalletModel().addConnection(con)

        return EndpointMessage(didexRequest)
    }
}

