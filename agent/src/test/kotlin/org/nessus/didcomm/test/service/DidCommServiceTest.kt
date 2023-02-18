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
package org.nessus.didcomm.test.service

import id.walt.common.prettyPrint
import io.kotest.matchers.shouldBe
import mu.KotlinLogging
import org.didcommx.didcomm.common.Typ
import org.didcommx.didcomm.message.MessageBuilder
import org.didcommx.didcomm.model.PackEncryptedParams
import org.didcommx.didcomm.model.PackPlaintextParams
import org.didcommx.didcomm.model.PackSignedParams
import org.didcommx.didcomm.model.UnpackParams
import org.nessus.didcomm.did.DidDocV2
import org.nessus.didcomm.did.DidMethod
import org.nessus.didcomm.model.Wallet
import org.nessus.didcomm.service.WaltIdDidDoc
import org.nessus.didcomm.service.WaltIdDidService
import org.nessus.didcomm.test.AbstractAgentTest
import org.nessus.didcomm.test.Alice
import org.nessus.didcomm.test.Faber
import java.util.UUID

class DidCommServiceTest: AbstractAgentTest() {
    val log = KotlinLogging.logger {}

    @Test
    fun testPlaintext() {

        val message = MessageBuilder(
                id = "${UUID.randomUUID()}",
                body = mapOf("content" to "Alice, you're smashing"),
                type = "http://didcomm/some-protocol/1.0")
            .build()

        val packResult = didComm.packPlaintext(
            PackPlaintextParams.builder(message)
                .build()
        )
        log.info { packResult.packedMessage.prettyPrint() }

        val unpackResult = didComm.unpack(
            UnpackParams.Builder(packResult.packedMessage)
                .build()
        )
        val mdata = unpackResult.metadata
        unpackResult.message shouldBe message
        mdata.encrypted shouldBe false
        mdata.authenticated shouldBe false
    }

    @Test
    fun testSigned() {

        val faber = Wallet.Builder(Faber.name).build()
        try {
            val faberDid = faber.createDid(DidMethod.KEY)

            val waltDidDoc: WaltIdDidDoc = WaltIdDidService.load(faberDid.qualified)
            log.info { "WaltIdDidDoc ${waltDidDoc.encodePretty()}" }

            val didDocV2: DidDocV2 = didService.loadDidDocument(faberDid.qualified)
            log.info { "DidDocV2 ${didDocV2.encodePretty()}" }

            val message = MessageBuilder(
                    id = "${UUID.randomUUID()}",
                    body = mapOf("content" to "Alice, you're smashing"),
                    type = Typ.Plaintext.typ)
                .from(faberDid.qualified)
                .build()

            val packResult = didComm.packSigned(
                PackSignedParams.builder(message, faberDid.qualified)
                    .build()
            )
            log.info { packResult.packedMessage.prettyPrint() }

            val unpackResult = didComm.unpack(
                UnpackParams.Builder(packResult.packedMessage)
                    .build()
            )
            val mdata = unpackResult.metadata
            unpackResult.message shouldBe message
            mdata.encrypted shouldBe false
            mdata.authenticated shouldBe true
            mdata.anonymousSender shouldBe false

        } finally {
            removeWallet(faber.id)
        }
    }


    @Test
    fun testEncrypted() {

        val faber = Wallet.Builder(Faber.name).build()
        val alice = Wallet.Builder(Alice.name).build()
        try {
            val faberDid = faber.createDid(DidMethod.KEY)
            val aliceDid = alice.createDid(DidMethod.KEY)

            val waltDidDoc: WaltIdDidDoc = WaltIdDidService.load(faberDid.qualified)
            log.info { "WaltIdDidDoc ${waltDidDoc.encodePretty()}" }

            val didDocV2: DidDocV2 = didService.loadDidDocument(faberDid.qualified)
            log.info { "DidDocV2 ${didDocV2.encodePretty()}" }

            val message = MessageBuilder(
                id = "${UUID.randomUUID()}",
                body = mapOf("content" to "Alice, you're smashing"),
                type = Typ.Plaintext.typ)
                .from(faberDid.qualified)
                .to(listOf(aliceDid.qualified))
                .build()

            val packResult = didComm.packEncrypted(
                PackEncryptedParams.builder(message, aliceDid.qualified)
                    .signFrom(faberDid.qualified)
                    .from(faberDid.qualified)
                    .build()
            )
            log.info { packResult.packedMessage.prettyPrint() }

            val unpackResult = didComm.unpack(
                UnpackParams.Builder(packResult.packedMessage)
                    .build()
            )
            val mdata = unpackResult.metadata
            unpackResult.message shouldBe message
            mdata.encrypted shouldBe true
            mdata.authenticated shouldBe true
            mdata.anonymousSender shouldBe false

        } finally {
            removeWallet(alice.id)
            removeWallet(faber.id)
        }
    }
}
