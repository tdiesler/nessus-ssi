/*-
 * #%L
 * Nessus DIDComm :: Services :: Agent
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
package org.nessus.didcomm.service

import org.nessus.didcomm.model.MessageExchange
import org.nessus.didcomm.protocol.*
import org.nessus.didcomm.util.AttachmentKey
import kotlin.reflect.KClass

val ENCRYPTED_ENVELOPE_V1 = ProtocolKey("https://rfc0019/application/didcomm-enc-env", EncryptionEnvelopeV1::class)
val DIDEXCHANGE_PROTOCOL_V1 = ProtocolKey("https://didcomm.org/didexchange/1.0", DidExchangeV1Protocol::class)
val TRUST_PING_PROTOCOL_V1 = ProtocolKey("https://didcomm.org/trust_ping/1.0", TrustPingV1Protocol::class)
val BASIC_MESSAGE_PROTOCOL_V1 = ProtocolKey("https://didcomm.org/basicmessage/1.0", BasicMessageV1Protocol::class)
val OUT_OF_BAND_PROTOCOL_V1 = ProtocolKey("https://didcomm.org/out-of-band/1.1", OutOfBandV1Protocol::class)

val TRUST_PING_PROTOCOL_V2 = ProtocolKey("https://didcomm.org/trust_ping/2.0-preview", RFC0048TrustPingProtocolV2::class)
val BASIC_MESSAGE_PROTOCOL_V2 = ProtocolKey("https://didcomm.org/basicmessage/2.0-preview", BasicMessageProtocolV2::class)
val OUT_OF_BAND_PROTOCOL_V2 = ProtocolKey("https://didcomm.org/out-of-band/2.0-preview", OutOfBandV2Protocol::class)
val ISSUE_CREDENTIAL_PROTOCOL_V3 = ProtocolKey("https://didcomm.org/issue-credential/3.0", IssueCredentialV3Protocol::class)
val REPORT_PROBLEM_PROTOCOL_V2 = ProtocolKey("https://didcomm.org/report-problem/2.0", ReportProblemProtocolV2::class)

class ProtocolKey<T: Protocol<T>>(uri: String, type: KClass<T>): AttachmentKey<T>(uri, type) {
    val uri get() = this.name
}

object ProtocolService : ObjectService<ProtocolService>() {

    override fun getService() = apply { }

    private val supportedProtocols: List<ProtocolKey<*>> get() = listOf(

        ENCRYPTED_ENVELOPE_V1,
        DIDEXCHANGE_PROTOCOL_V1,
        TRUST_PING_PROTOCOL_V1,
        BASIC_MESSAGE_PROTOCOL_V1,
        OUT_OF_BAND_PROTOCOL_V1,

        TRUST_PING_PROTOCOL_V2,
        BASIC_MESSAGE_PROTOCOL_V2,
        OUT_OF_BAND_PROTOCOL_V2,
        ISSUE_CREDENTIAL_PROTOCOL_V3,
    )

    fun findProtocolKey(uri: String): ProtocolKey<*> {
        val keyPair = supportedProtocols.find { it.uri == uri }
        checkNotNull(keyPair) { "Unknown protocol uri: $uri" }
        return keyPair
    }

    @Suppress("UNCHECKED_CAST")
    fun <T: Protocol<T>> getProtocol(key: ProtocolKey<T>, mex: MessageExchange): T {
        return when(key) {

            ENCRYPTED_ENVELOPE_V1 -> EncryptionEnvelopeV1()
            DIDEXCHANGE_PROTOCOL_V1 -> DidExchangeV1Protocol(mex)
            TRUST_PING_PROTOCOL_V1 -> TrustPingV1Protocol(mex)
            BASIC_MESSAGE_PROTOCOL_V1 -> BasicMessageV1Protocol(mex)
            OUT_OF_BAND_PROTOCOL_V1 -> OutOfBandV1Protocol(mex)

            TRUST_PING_PROTOCOL_V2 -> RFC0048TrustPingProtocolV2(mex)
            BASIC_MESSAGE_PROTOCOL_V2 -> BasicMessageProtocolV2(mex)
            OUT_OF_BAND_PROTOCOL_V2 -> OutOfBandV2Protocol(mex)
            ISSUE_CREDENTIAL_PROTOCOL_V3 -> IssueCredentialV3Protocol(mex)

            else -> throw IllegalStateException("Unknown protocol: $key")
        } as T
    }

    fun getProtocolKey(messageType: String): ProtocolKey<*>? {
        return supportedProtocols.firstOrNull { messageType.startsWith(it.name) }
    }
}
