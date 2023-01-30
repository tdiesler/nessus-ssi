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

package org.nessus.didcomm.wallet

import com.google.gson.annotations.SerializedName
import id.walt.crypto.KeyAlgorithm
import org.nessus.didcomm.agent.*
import org.nessus.didcomm.agent.AgentConfiguration.Companion.agentConfiguration
import org.nessus.didcomm.did.Did
import org.nessus.didcomm.model.Connection
import org.nessus.didcomm.model.Invitation
import org.nessus.didcomm.model.WalletModel
import org.nessus.didcomm.service.DataModelService
import org.nessus.didcomm.service.WalletPlugin
import org.nessus.didcomm.service.WalletService
import org.slf4j.event.Level

enum class LedgerRole {
    TRUSTEE,
    ENDORSER
}

enum class DidMethod(val value: String) {
    KEY("key"),
    SOV("sov");
    companion object {
        fun fromValue(value: String) = DidMethod.valueOf(value.uppercase())
    }
}

enum class AgentType(val value: String) {
    @SerializedName("AcaPy")
    ACAPY("AcaPy"),
    @SerializedName("Nessus")
    NESSUS("Nessus");
    companion object {
        fun fromValue(value: String) = AgentType.valueOf(value.uppercase())
    }
}

enum class StorageType(val value: String) {
    IN_MEMORY("in_memory"),
    INDY("indy");
    companion object {
        fun fromValue(value: String) = StorageType.valueOf(value.uppercase())
    }
}

fun Wallet.toWalletModel(): WalletModel {
    val modelService = DataModelService.getService()
    return modelService.getWallet(this.id) as WalletModel
}

/**
 * A NessusWallet provides access to general wallet functionality.
 *
 * All work is delegated to the WalletService, which maintains the set
 * of all wallets known by the system.
 *
 * Agent specific functionality is handled by a WalletPlugin which is a
 * stateful entity associated with this wallet
 */
class Wallet(
    val id: String,
    val name: String,
    val agentType: AgentType,
    val storageType: StorageType,
    val authToken: String? = null,
    val options: Map<String, Any> = mapOf(),
) {
    val endpointUrl: String get() = walletPlugin.getEndpointUrl(this)

    internal val walletPlugin: WalletPlugin = when (agentType) {
        AgentType.ACAPY -> AriesWalletPlugin()
        AgentType.NESSUS -> NessusWalletPlugin()
    }

    private val walletService get() = WalletService.getService()

    private val interceptorLogLevel = Level.INFO
    private var webSocketClient: WebSocketClient? = null

    // [TODO] Abstract the Agent client
    fun adminClient(): AriesClient? {
        return if (agentType == AgentType.ACAPY) {
            val config = agentConfiguration(options)
            AriesClientFactory.adminClient(config, level = interceptorLogLevel)
        } else null
    }

    // [TODO] Abstract the Agent client
    fun walletClient(): AriesClient? {
        return if (agentType == AgentType.ACAPY) {
            val config = agentConfiguration(options)
            AriesClientFactory.walletClient(this, config, level = interceptorLogLevel)
        } else null
    }

    fun getWebSocketUrl(): String? {
        return if (agentType == AgentType.ACAPY) {
            agentConfiguration(options).wsUrl
        } else null
    }

    fun openWebSocket(eventListener: (wse: WebSocketEvent) -> Unit) {
        val webSocketListener = WebSocketListener(this, eventListener)
        webSocketClient = WebSocketClient(this, webSocketListener).openWebSocket()
    }

    fun closeWebSocket() {
        webSocketClient?.closeWebSocket()
        webSocketClient = null
    }

    fun closeWallet() {
        closeWebSocket()
    }

    fun createDid(method: DidMethod? = null, algorithm: KeyAlgorithm? = null, seed: String? = null): Did {
        return walletService.createDid(this, method, algorithm, seed)
    }

    fun getPublicDid(): Did? {
        return walletService.getPublicDid(this)
    }

    fun getConnection(conId: String): Connection? {
        return toWalletModel().getConnection(conId)
    }

    fun findConnection(verkey: String): Connection? {
        return toWalletModel().findConnection { it.myVerkey == verkey }
    }

    fun getInvitation(invId: String): Invitation? {
        return toWalletModel().getInvitation(invId)
    }

    fun removeConnections() {
        return walletService.removeConnections(this)
    }

    data class Builder (var walletName: String) {
        var agentType: AgentType? = null
        var storageType: StorageType? = null
        var options: MutableMap<String, Any> = mutableMapOf()
        var walletKey: String? = null
        var ledgerRole: LedgerRole? = null
        var trusteeWallet: Wallet? = null
        var publicDidMethod: DidMethod? = null
        var mayExist: Boolean = false

        fun agentType(agentType: AgentType?) = apply { this.agentType = agentType }
        fun options(options: Map<String, Any>) = apply { this.options.putAll(options) }
        fun storageType(storageType: StorageType?) = apply { this.storageType = storageType }
        fun walletKey(walletKey: String?) = apply { this.walletKey = walletKey }
        fun publicDidMethod(didMethod: DidMethod?) = apply { this.publicDidMethod = didMethod }
        fun ledgerRole(ledgerRole: LedgerRole?) = apply { this.ledgerRole = ledgerRole }
        fun trusteeWallet(trusteeWallet: Wallet?) = apply { this.trusteeWallet = trusteeWallet }
        fun mayExist(mayExist: Boolean) = apply { this.mayExist = mayExist }

        fun build(): Wallet = WalletService.getService().createWallet(
            WalletConfig(
                walletName, agentType, storageType, walletKey, ledgerRole, trusteeWallet, publicDidMethod,
                options.toMap(), mayExist
            ))

    }

    override fun toString(): String {
        var redactedToken: String? = null
        if (authToken != null)
            redactedToken = authToken.substring(0, 6) + "..." + authToken.substring(authToken.length - 6)
        return "Wallet(id='$id', agent=$agentType, type=$storageType, alias=$name, endpointUrl=$endpointUrl, options=$options, authToken=$redactedToken)"
    }

    // Private ---------------------------------------------------------------------------------------------------------

}

data class WalletConfig(
    val name: String,
    val agentType: AgentType?,
    val storageType: StorageType?,
    val walletKey: String?,
    val ledgerRole: LedgerRole?,
    val trusteeWallet: Wallet?,
    val publicDidMethod: DidMethod?,
    val walletOptions: Map<String, Any>,
    val mayExist: Boolean
)
