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
import id.walt.servicematrix.BaseService
import id.walt.servicematrix.ServiceProvider
import mu.KotlinLogging
import org.hyperledger.aries.api.multitenancy.CreateWalletTokenRequest
import org.nessus.didcomm.agent.AgentConfiguration
import org.nessus.didcomm.agent.AriesAgent
import org.nessus.didcomm.did.Did
import org.nessus.didcomm.did.DidMethod
import org.nessus.didcomm.model.AgentType
import org.nessus.didcomm.model.StorageType
import org.nessus.didcomm.model.Wallet
import org.nessus.didcomm.wallet.*
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.isReadable

class WalletService : BaseService() {
    private val log = KotlinLogging.logger {}
    override val implementation get() = serviceImplementation<WalletService>()

    companion object: ServiceProvider {
        private val implementation = WalletService()
        override fun getService() = implementation
    }

    init {
        initAcaPyWallets()
    }

    val modelService get() = ModelService.getService()
    val wallets get() = modelService.wallets

    fun createWallet(config: WalletConfig): Wallet {
        val maybeWallet = findByName(config.name)
        val agentType = config.agentType ?: AgentType.NESSUS
        val storageType = config.storageType ?: StorageType.IN_MEMORY
        if (config.mayExist && maybeWallet != null) {
            check(maybeWallet.agentType == agentType) {"Wallet ${config.name} exists, with other agent: ${maybeWallet.agentType}"}
            check(maybeWallet.storageType == storageType)  {"Wallet ${config.name} exists, with other type: ${maybeWallet.storageType}"}
            return maybeWallet
        }

        val wallet = when(config.agentType!!) {
            AgentType.ACAPY -> AcapyWalletPlugin().createWallet(config)
            AgentType.NESSUS -> NessusWalletPlugin().createWallet(config)
        }

        addWallet(wallet)
        return wallet
    }

    fun addWallet(wallet: Wallet) {
        modelService.addWallet(wallet)
    }

    fun removeWallet(id: String): Wallet? {
        val wallet = modelService.getWallet(id)
        if (wallet != null) {
            wallet.walletPlugin.removeWallet(wallet)
            modelService.removeWallet(id)
        }
        return wallet
    }

    fun findByName(name: String): Wallet? {
        return modelService.findWallet { it.name.lowercase() == name.lowercase() }
    }

    /**
     * Create a Did for the given wallet
     *
     * Nessus Dids are created locally and have their associated keys in the {@see KeyStoreService}
     */
    fun createDid(wallet: Wallet, method: DidMethod? = null, keyAlias: String? = null): Did {
        val did = wallet.walletPlugin.createDid(wallet, method, keyAlias)
        wallet.addDid(did)
        return did
    }

    /**
     * Get the (optional) public Did for the given wallet
     */
    fun getPublicDid(wallet: Wallet): Did? {
        return wallet.walletPlugin.publicDid(wallet)
    }

    fun removeConnections(wallet: Wallet) {
        wallet.walletPlugin.removeConnections(wallet)
    }

    // Private ---------------------------------------------------------------------------------------------------------

    @Suppress("UNCHECKED_CAST")
    private fun initAcaPyWallets() {
        val agentConfig = AgentConfiguration.defaultConfiguration
        val adminClient = AriesAgent.adminClient(agentConfig)

        // Initialize wallets from Siera config
        readSieraConfig()?.filterKeys { k -> k != "default" }?.forEach {
            val walletName = it.key
            val values = it.value as Map<String, String>
            val agent = values["agent"] ?: "aca-py"
            check(agent == "aca-py") { "Unsupported agent: $agent" }
            val endpointUrl = values["endpoint"] as String
            val authToken = values["auth_token"]
            val walletRecord = adminClient.multitenancyWallets(walletName).get().firstOrNull()
            walletRecord?.run {
                val walletId = walletRecord.walletId
                val wallet = AcapyWallet(
                    walletId,
                    walletName,
                    AgentType.ACAPY,
                    StorageType.INDY,
                    endpointUrl,
                    authToken=authToken
                )
                addWallet(wallet)
            }
        }

        // Initialize wallets from AcaPy
        adminClient.multitenancyWallets(null).get()
            .filter { modelService.getWallet(it.walletId) == null }
            .forEach {
                val walletId = it.walletId
                val alias = it.settings.walletName
                val storageType = StorageType.valueOf(it.settings.walletType.name)
                val tokReq = CreateWalletTokenRequest.builder().build()
                val tokRes = adminClient.multitenancyWalletToken(walletId, tokReq).get()
                val wallet = AcapyWallet(
                    walletId,
                    alias,
                    AgentType.ACAPY,
                    storageType,
                    agentConfig.userUrl,
                    authToken=tokRes.token
                )
                addWallet(wallet)
            }
        log.info { "Done Wallet Init ".padEnd(180, '=') }
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

interface WalletPlugin {

    fun createWallet(config: WalletConfig): Wallet

    fun removeWallet(wallet: Wallet)

    fun createDid(wallet: Wallet, method: DidMethod?, keyAlias: String? = null): Did

    fun publicDid(wallet: Wallet): Did?

    fun removeConnections(wallet: Wallet)
}

