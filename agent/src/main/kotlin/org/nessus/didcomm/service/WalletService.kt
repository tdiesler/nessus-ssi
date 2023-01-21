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

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import id.walt.crypto.KeyAlgorithm
import id.walt.servicematrix.BaseService
import id.walt.servicematrix.ServiceProvider
import mu.KotlinLogging
import org.hyperledger.aries.api.multitenancy.CreateWalletTokenRequest
import org.nessus.didcomm.agent.AriesAgent
import org.nessus.didcomm.did.Did
import org.nessus.didcomm.model.WalletModel
import org.nessus.didcomm.wallet.AgentType
import org.nessus.didcomm.wallet.AriesWalletPlugin
import org.nessus.didcomm.wallet.DidMethod
import org.nessus.didcomm.wallet.NessusWalletPlugin
import org.nessus.didcomm.wallet.StorageType
import org.nessus.didcomm.wallet.Wallet
import org.nessus.didcomm.wallet.WalletConfig
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.isReadable

class WalletService : BaseService() {
    override val implementation get() = serviceImplementation<WalletService>()

    private val log = KotlinLogging.logger {}

    private val walletStore get() = WalletStoreService.getService()
    private val modelService get() = ModelManagerService.getService()

    companion object: ServiceProvider {
        private val implementation = WalletService()
        override fun getService() = implementation
    }

    init {
        val adminClient = AriesAgent.adminClient()

        // Initialize wallets from Siera config
        readSieraConfig()?.filterKeys { k -> k != "default" }?.forEach {
            @Suppress("UNCHECKED_CAST")
            val values = it.value as Map<String, String>
            val agent = values["agent"] ?: "aca-py"
            check(agent == "aca-py") { "Unsupported agent: $agent" }
            val alias = it.key
            val authToken = values["auth_token"]
            val walletRecord = adminClient.multitenancyWallets(alias).get().firstOrNull()
            walletRecord?.run {
                val walletId = walletRecord.walletId
                val wallet = Wallet(walletId, alias, AgentType.ACAPY, StorageType.INDY, authToken=authToken)
                addWallet(wallet)
            }
        }

        // Initialize wallets from ACAPy
        adminClient.multitenancyWallets(null).get()
            .filter { getWallet(it.walletId) == null }
            .forEach {
                val walletId = it.walletId
                val alias = it.settings.walletName
                val storageType = StorageType.valueOf(it.settings.walletType.name)
                val tokReq = CreateWalletTokenRequest.builder().build()
                val tokRes = adminClient.multitenancyWalletToken(walletId, tokReq).get()
                val wallet = Wallet(walletId, alias, AgentType.ACAPY, storageType, authToken=tokRes.token)
                addWallet(wallet)
            }
        log.info { "Done Wallet Init ".padEnd(180, '=') }
    }

    fun createWallet(config: WalletConfig): Wallet {
        val maybeWallet = findByName(config.name)
        val agentType = config.agentType ?: AgentType.NESSUS
        val storageType = config.storageType ?: StorageType.IN_MEMORY
        if (config.mayExist && maybeWallet != null) {
            check(maybeWallet.agentType == agentType) {"Wallet ${config.name} exists, with other agent: ${maybeWallet.agentType}"}
            check(maybeWallet.storageType == storageType)  {"Wallet ${config.name} exists, with other type: ${maybeWallet.storageType}"}
            return maybeWallet
        }
        val wallet = walletServicePlugin(agentType).createWallet(config)
        addWallet(wallet)
        return wallet
    }

    fun addWallet(wallet: Wallet) {
        check(findByName(wallet.name) == null) {"Wallet already exists: ${wallet.name}"}
        log.info {"Add: $wallet" }
        walletStore.addWallet(wallet)
        modelService.addWallet(WalletModel.fromWallet(wallet))
    }

    fun removeWallet(id: String): Wallet? {
        val wallet = getWallet(id)
        if (wallet != null) {
            log.info {"Remove: $wallet" }
            walletServicePlugin(wallet.agentType).removeWallet(wallet)
            walletStore.removeWallet(id)
            modelService.removeWallet(id)
        }
        return wallet
    }

    fun getWallets(): List<Wallet> {
        return walletStore.wallets
    }

    fun getWallet(id: String): Wallet? {
        return walletStore.getWallet(id)
    }

    fun findByName(name: String): Wallet? {
        return walletStore.findByName(name)
    }

    fun findByVerkey(verkey: String): Wallet? {
        return walletStore.findByVerkey(verkey)
    }

    /**
     * Create a Did for the given wallet
     *
     * Nessus Dids are created locally and have their associated keys in the {@see KeyStoreService}
     */
    fun createDid(wallet: Wallet, method: DidMethod?, algorithm: KeyAlgorithm?, seed: String?): Did {
        val did = wallet.walletPlugin.createDid(wallet, method, algorithm, seed)
        log.info { "New DID for ${wallet.name}: $did" }
        walletStore.addDid(wallet.id, did)
        return did
    }

    /**
     * List Dids registered with the given wallet
     */
    fun listDids(wallet: Wallet): List<Did> {
        return wallet.walletPlugin.listDids(wallet)
    }

    fun addConnection(wallet: Wallet, con: PeerConnection) {
        walletStore.addPeerConnection(wallet.id, con)
    }

    fun getConnection(wallet: Wallet, conId: String): PeerConnection? {
        return walletStore.getPeerConnection(wallet.id, conId)
    }

    fun listConnections(wallet: Wallet): List<PeerConnection> {
        return walletStore.listPeerConnections(wallet.id)
    }

    fun removeConnections(wallet: Wallet) {
        wallet.walletPlugin.removeConnections(wallet)
        return walletStore.removePeerConnections(wallet.id)
    }

    /**
     * Get the (optional) public Did for the given wallet
     */
    fun getPublicDid(wallet: Wallet): Did? {
        return wallet.walletPlugin.publicDid(wallet)
    }

    // Private ---------------------------------------------------------------------------------------------------------

    private fun walletServicePlugin(agentType: AgentType): WalletServicePlugin {
        return when(agentType) {
            AgentType.ACAPY -> AriesWalletPlugin()
            AgentType.NESSUS -> NessusWalletPlugin()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun readSieraConfig(): Map<String, Any>? {
        val mapper = ObjectMapper(YAMLFactory()).registerKotlinModule()

        val homeDir = System.getenv("HOME")
        val configPath = Paths.get("$homeDir/.config/siera/config.yaml")

        return if (configPath.isReadable()) {
            Files.newBufferedReader(configPath).use {
                val config = mapper.readValue(it, Map::class.java)
                return config["configurations"] as Map<String, Any>
            }
        } else null
    }
}

interface WalletServicePlugin {

    fun createWallet(config: WalletConfig): Wallet

    fun removeWallet(wallet: Wallet)
}

interface WalletPlugin {

    fun getEndpointUrl(wallet: Wallet): String

    fun createDid(
        wallet: Wallet,
        method: DidMethod?,
        algorithm: KeyAlgorithm? = null,
        seed: String? = null): Did

    fun publicDid(wallet: Wallet): Did?

    fun listDids(wallet: Wallet): List<Did>

    fun removeConnections(wallet: Wallet)
}

