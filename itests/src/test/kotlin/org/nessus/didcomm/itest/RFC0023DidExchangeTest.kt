/*-
 * #%L
 * Nessus DIDComm :: ITests
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
package org.nessus.didcomm.itest

import org.junit.jupiter.api.Test
import org.nessus.didcomm.model.ConnectionState
import org.nessus.didcomm.protocol.MessageExchange
import org.nessus.didcomm.service.RFC0023_DIDEXCHANGE
import org.nessus.didcomm.service.RFC0048_TRUST_PING
import org.nessus.didcomm.service.RFC0434_OUT_OF_BAND
import org.nessus.didcomm.wallet.AgentType
import org.nessus.didcomm.wallet.Wallet
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.fail


/**
 * Aries RFC 0434: Out-of-Band Protocol 1.1
 * https://github.com/hyperledger/aries-rfcs/tree/main/features/0434-outofband
 *
 * Aries RFC 0023: DID Exchange Protocol 1.0
 * https://github.com/hyperledger/aries-rfcs/tree/main/features/0023-did-exchange
 *
 * DIDComm - Out Of Band Messages
 * https://identity.foundation/didcomm-messaging/spec/#out-of-band-messages
 */
class RFC0023DidExchangeTest : AbstractIntegrationTest() {

    @Test
    fun test_FaberAcapy_invites_AliceNessus() {

        /**
         * Create the Wallets
         */

        val faber = getWalletByAlias(Faber.name) ?: fail("No Inviter")

        val alice = Wallet.Builder(Alice.name)
            .options(NESSUS_OPTIONS_01)
            .agentType(AgentType.NESSUS)
            .build()

        try {
            endpointService.startEndpoint(alice).use {

                /**
                 * Inviter (Faber) creates an Out-of-Band Invitation
                 * Invitee (Alice) receives and accepts the Invitation
                 * Requester (Alice) send the DidEx Request
                 * Responder (Faber) accepts the DidEx Request and sends a Response
                 * Requester (Alice) sends the DidEx Complete message
                 * Requester (Alice) sends a Trust Ping
                 * Responder (Faber) sends a Trust Ping Response
                 */

                val pcon = MessageExchange()
                    .withProtocol(RFC0434_OUT_OF_BAND)
                    .createOutOfBandInvitation(faber, "Faber invites Alice")
                    .receiveOutOfBandInvitation(alice)

                    .withProtocol(RFC0023_DIDEXCHANGE)
                    .sendDidExchangeRequest()
                    .awaitDidExchangeResponse(5, TimeUnit.SECONDS)
                    .sendDidExchangeComplete()

                    .withProtocol(RFC0048_TRUST_PING)
                    .sendTrustPing()
                    .awaitTrustPingResponse(5, TimeUnit.SECONDS)

                    .getMessageExchange()
                    .getConnection()

                assertEquals(ConnectionState.ACTIVE, pcon?.state)
            }
        } finally {
            faber.removeConnections()
            removeWallet(Alice.name)
        }
    }

    //@Test
    fun test_AliceNessus_invites_FaberAcapy() {

        /**
         * Create the Wallets
         */

        val faber = getWalletByAlias(Faber.name) ?: fail("No Inviter")

        val alice = Wallet.Builder(Alice.name)
            .options(NESSUS_OPTIONS_01)
            .agentType(AgentType.NESSUS)
            .build()

        try {
            endpointService.startEndpoint(alice).use {

                /**
                 * Inviter (Alice) creates an Out-of-Band Invitation
                 * Invitee (Faber) receives and accepts the Invitation
                 * Requester (Faber) send the DidEx Request
                 * Responder (Alice) accepts the DidEx Request and sends a Response
                 * Requester (Faber) sends the DidEx Complete message
                 * Requester (Faber) sends a Trust Ping
                 * Responder (Alice) sends a Trust Ping Response
                 */

                val pcon = MessageExchange()
                    .withProtocol(RFC0434_OUT_OF_BAND)
                    .createOutOfBandInvitation(alice, "Faber invites Alice")
                    .receiveOutOfBandInvitation(faber)

                    .withProtocol(RFC0023_DIDEXCHANGE)
                    .sendDidExchangeRequest()
                    .awaitDidExchangeResponse(5, TimeUnit.SECONDS)
                    .sendDidExchangeComplete()

                    .withProtocol(RFC0048_TRUST_PING)
                    .sendTrustPing()
                    .awaitTrustPingResponse(5, TimeUnit.SECONDS)

                    .getMessageExchange()
                    .getConnection()

                assertEquals(ConnectionState.ACTIVE, pcon?.state)
            }
        } finally {
            faber.removeConnections()
            removeWallet(Alice.name)
        }
    }
}
