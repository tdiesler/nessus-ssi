package org.nessus.didcomm.protocol

import id.walt.common.prettyPrint
import mu.KotlinLogging
import org.hyperledger.acy_py.generated.model.SendMessage
import org.nessus.didcomm.agent.AriesClient
import org.nessus.didcomm.model.Connection
import org.nessus.didcomm.model.ConnectionState
import org.nessus.didcomm.model.toWallet
import org.nessus.didcomm.protocol.RFC0019EncryptionEnvelope.Companion.RFC0019_ENCRYPTED_ENVELOPE_MEDIA_TYPE
import org.nessus.didcomm.service.RFC0095_BASIC_MESSAGE
import org.nessus.didcomm.util.trimJson
import org.nessus.didcomm.wallet.AgentType
import org.nessus.didcomm.wallet.Wallet
import java.util.*

/**
 * Aries RFC 0095: Basic Message Protocol 1.0
 * https://github.com/hyperledger/aries-rfcs/tree/main/features/0095-basic-message
 */
class RFC0095BasicMessageProtocol(mex: MessageExchange): Protocol<RFC0095BasicMessageProtocol>(mex) {

    override val protocolUri = RFC0095_BASIC_MESSAGE.uri
    override val log = KotlinLogging.logger {}

    companion object {
        val RFC0095_BASIC_MESSAGE_TYPE = "${RFC0095_BASIC_MESSAGE.uri}/message"
    }

    override fun invokeMethod(to: Wallet, messageType: String): Boolean {
        when (messageType) {
            RFC0095_BASIC_MESSAGE_TYPE -> receiveMessage(to)
            else -> throw IllegalStateException("Unsupported message type: $messageType")
        }
        return true
    }

    fun sendMessage(pcon: Connection, message: String): RFC0095BasicMessageProtocol {

        check(pcon.state == ConnectionState.ACTIVE) { "Connection not active: $pcon" }

        val sender = modelService.findWalletByVerkey(pcon.myDid.verkey)?.toWallet()
        checkNotNull(sender) { "No sender wallet" }

        val rfc0095 = when(sender.agentType) {
            AgentType.ACAPY -> sendMessageAcapy(sender, pcon, message)
            AgentType.NESSUS -> sendMessageNessus(sender, pcon, message)
        }

        return rfc0095
    }

    // Private ---------------------------------------------------------------------------------------------------------

    private fun sendMessageAcapy(sender: Wallet, pcon: Connection, message: String): RFC0095BasicMessageProtocol {

        // Use my previous MessageExchange
        val myMex = MessageExchange.findByVerkey(pcon.myVerkey)
        val rfc0095 = myMex.withProtocol(RFC0095_BASIC_MESSAGE)

        val fromClient = sender.walletClient() as AriesClient
        val basicMessage = SendMessage.builder().content(message).build()
        fromClient.connectionsSendMessage(pcon.id, basicMessage)

        val basicMsg = """
        {
            "@type": "$RFC0095_BASIC_MESSAGE_TYPE",
            "@id": "${UUID.randomUUID()}",
            "content": "$message",
            "sent_time": "$nowIso8601"
        }
        """.trimJson()

        myMex.addMessage(EndpointMessage(basicMsg))

        return rfc0095
    }

    private fun sendMessageNessus(sender: Wallet, pcon: Connection, message: String): RFC0095BasicMessageProtocol {

        // Use my previous MessageExchange
        val myMex = MessageExchange.findByVerkey(pcon.myVerkey)
        val rfc0095 = myMex.withProtocol(RFC0095_BASIC_MESSAGE)

        val basicMsg = """
        {
            "@type": "$RFC0095_BASIC_MESSAGE_TYPE",
            "@id": "${UUID.randomUUID()}",
            "sent_time": "$nowIso8601",
            "content": "$message"
        }
        """.trimJson()

        myMex.addMessage(EndpointMessage(basicMsg))

        val packedBasicMsg = RFC0019EncryptionEnvelope()
            .packEncryptedEnvelope(basicMsg, pcon.myDid, pcon.theirDid)

        val packedEpm = EndpointMessage(packedBasicMsg, mapOf(
            "Content-Type" to RFC0019_ENCRYPTED_ENVELOPE_MEDIA_TYPE
        ))

        dispatchToEndpoint(pcon.theirEndpointUrl, packedEpm)

        return rfc0095
    }

    private fun receiveMessage(receiver: Wallet): RFC0095BasicMessageProtocol {

        val bodyJson = mex.last.bodyAsJson
        log.info { "Received basic message: ${bodyJson.prettyPrint()}" }

        return this
    }
}

