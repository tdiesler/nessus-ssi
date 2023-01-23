package org.nessus.didcomm.protocol

import org.hyperledger.aries.api.trustping.PingRequest
import org.nessus.didcomm.agent.AriesClient
import org.nessus.didcomm.model.Connection
import org.nessus.didcomm.model.ConnectionState
import org.nessus.didcomm.protocol.RFC0019EncryptionEnvelope.Companion.RFC0019_ENCRYPTED_ENVELOPE_MEDIA_TYPE
import org.nessus.didcomm.service.RFC0048_TRUST_PING
import org.nessus.didcomm.util.AttachmentKey
import org.nessus.didcomm.util.gson
import org.nessus.didcomm.util.trimJson
import org.nessus.didcomm.wallet.AgentType
import org.nessus.didcomm.wallet.Wallet
import org.nessus.didcomm.wallet.toWalletModel
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Aries RFC 0048: Trust Ping Protocol 1.0
 * https://github.com/hyperledger/aries-rfcs/tree/main/features/0048-trust-ping
 */
class RFC0048TrustPingProtocol(mex: MessageExchange):
    Protocol<RFC0048TrustPingProtocol>(mex) {
    override val protocolUri = RFC0048_TRUST_PING.uri

    companion object {
        const val PROTOCOL_METHOD_SEND_PING = "/connections/send_ping"

        val RFC0048_TRUST_PING_MESSAGE_TYPE_PING = "${RFC0048_TRUST_PING.uri}/ping"
        val RFC0048_TRUST_PING_MESSAGE_TYPE_PING_RESPONSE = "${RFC0048_TRUST_PING.uri}/ping_response"
    }

    override fun invokeMethod(to: Wallet, messageType: String): Boolean {
        when (messageType) {
            RFC0048_TRUST_PING_MESSAGE_TYPE_PING_RESPONSE -> receiveTrustPingResponse(to)
            else -> throw IllegalStateException("Unsupported message type: $messageType")
        }
        return true
    }

    fun sendTrustPing(sender: Wallet): RFC0048TrustPingProtocol {

        // Assert attached connection
        val pcon = mex.getConnection()
        checkNotNull(pcon) { "No peer connection attached" }
        check(pcon.state.ordinal >= ConnectionState.COMPLETED.ordinal) { "Unexpected connection state: $pcon" }

        val pingRequest = buildTrustPingRequest(sender, pcon.id)

        // Start a new message exchange
        val pingMex = MessageExchange(pingRequest)

        // Attach the Connection
        val pconKey = AttachmentKey(Connection::class.java)
        pingMex.putAttachment(pconKey, pcon)

        // Register the response future with the message exchange
        val futureId = "${RFC0048_TRUST_PING_MESSAGE_TYPE_PING_RESPONSE}/${pingRequest.thid}"
        val futureKey = AttachmentKey(futureId, CompletableFuture::class.java)
        pingMex.putAttachment(futureKey, CompletableFuture<EndpointMessage>())

        val packedPingRequest = RFC0019EncryptionEnvelope()
            .packEncryptedEnvelope(pingRequest.bodyAsJson, pcon.myDid, pcon.theirDid)

        val packedEpm = EndpointMessage(packedPingRequest, mapOf(
            "Content-Type" to RFC0019_ENCRYPTED_ENVELOPE_MEDIA_TYPE
        ))

        return pingMex.withProtocol(RFC0048_TRUST_PING)
            .dispatchToEndpoint(pcon.theirEndpointUrl, packedEpm)
    }

    fun awaitTrustPingResponse(timeout: Int, unit: TimeUnit): RFC0048TrustPingProtocol {
        val pingThreadId = mex.last.thid as String
        val futureId = "${RFC0048_TRUST_PING_MESSAGE_TYPE_PING_RESPONSE}/$pingThreadId"
        val futureKey = AttachmentKey(futureId, CompletableFuture::class.java)
        val future = mex.getAttachment(futureKey)
        if (future != null) {
            log.info {"Wait on future: $futureKey"}
            future.get(timeout.toLong(), unit)
        }
        return this
    }

    @Suppress("UNCHECKED_CAST")
    private fun receiveTrustPingResponse(requester: Wallet): RFC0048TrustPingProtocol {
        val pingThreadId = mex.last.thid as String
        val futureId = "${RFC0048_TRUST_PING_MESSAGE_TYPE_PING_RESPONSE}/$pingThreadId"
        val futureKey = AttachmentKey(futureId, CompletableFuture::class.java)
        val future = mex.removeAttachment(futureKey) as? CompletableFuture<EndpointMessage>
        if (future != null) {
            log.info {"Complete future: $futureKey"}
            future.complete(mex.last)
        }

        // Set the connection state to ACTIVE
        val pcon = mex.getConnection()
        checkNotNull(pcon) { "No peer connection" }
        pcon.state = ConnectionState.ACTIVE

        return this
    }

    /**
     * Send a basic message to a connection
     */
    fun buildTrustPingRequest(sender: Wallet, conId: String): EndpointMessage {

        return if (sender.agentType == AgentType.ACAPY)
            sendTrustPingAcapy(sender, conId)
        else {
            sendTrustPingNessus(sender, conId)
        }
    }

    // Private ---------------------------------------------------------------------------------------------------------

    private fun sendTrustPingAcapy(sender: Wallet, conId: String): EndpointMessage {

        val pcon = sender.toWalletModel().getConnection(conId)
        checkNotNull(pcon) { "Unknown connection id: $conId" }

        val senderClient = sender.walletClient() as AriesClient
        val pingRequest = PingRequest.builder().comment("ping").build()
        val pingResponse = senderClient.connectionsSendPing(conId, pingRequest).get()
        return EndpointMessage(gson.toJson(pingResponse))
    }

    private fun sendTrustPingNessus(sender: Wallet, conId: String): EndpointMessage {

        val pcon = sender.toWalletModel().getConnection(conId)
        checkNotNull(pcon) { "Unknown connection id: $conId" }

        val trustPing = """
                {
                    "@type": "$RFC0048_TRUST_PING_MESSAGE_TYPE_PING",
                    "@id": "${UUID.randomUUID()}",
                    "response_requested": True
                }
                """.trimJson()

        return EndpointMessage(trustPing)
    }
}
