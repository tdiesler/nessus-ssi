/*-
 * #%L
 * Nessus DIDComm :: CLI
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
package org.nessus.didcomm.cli

import org.nessus.didcomm.model.AgentType
import org.nessus.didcomm.model.ConnectionState
import org.nessus.didcomm.model.Wallet
import org.nessus.didcomm.protocol.MessageExchange
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters

@Command(
    name = "wallet",
    description = ["Multitenant wallet commands"],
)
class WalletCommands: AbstractBaseCommand() {

    @Command(name = "list", description = ["List available wallets"])
    fun listWallets(

        @Option(names = ["-v", "--verbose"], description = ["Verbose terminal output"])
        verbose: Boolean = false
    ) {
        modelService.wallets.forEachIndexed { idx, w ->
            val wstr = if (verbose) w.encodeJson(true) else w.shortString()
            echo("[$idx] $wstr")
        }
    }

    @Command(name = "show", description = ["Show wallet details"])
    fun showWallet(

        @Parameters(description = ["The wallet alias"])
        alias: String,

        @Option(names = ["-v", "--verbose"], description = ["Verbose terminal output"])
        verbose: Boolean = false,
    ) {
        val w = getWalletFromAlias(alias)
        if (verbose)
            echo(w.encodeJson(true))
        else
            echo(w.shortString())
    }

    @Command(name = "create", description = ["Create a wallet for a given agent"])
    fun createWallet(
        @Option(names = ["-n", "--name"], required = true, description = ["The wallet name"])
        name: String,

        @Option(names = ["-a", "--agent"], description = ["The agent type (default=Nessus)"], defaultValue = "Nessus")
        agent: String?,

        @Option(names = ["-v", "--verbose"], description = ["Verbose terminal output"])
        verbose: Boolean = false
    ) {
        val wallet = Wallet.Builder(name)
            .agentType(AgentType.fromValue(agent!!))
            .build()
        cliService.putContextWallet(wallet)
        if (verbose)
            echo("Wallet created\n${wallet.encodeJson(true)}")
        else
            echo("Wallet created: ${wallet.shortString()}")
    }

    @Command(name = "remove", description = ["Remove and delete a given wallet"])
    fun removeWallet(
        @Parameters(description = ["The wallet alias"])
        alias: String
    ) {
        getWalletFromAlias(alias).also { wallet ->
            val walletAtt = cliService.getAttachment(MessageExchange.WALLET_ATTACHMENT_KEY)
            if (wallet.id == walletAtt?.id) {
                cliService.removeAttachment(MessageExchange.WALLET_ATTACHMENT_KEY)
            }
            walletService.removeWallet(wallet.id)
            echo("Wallet removed: ${wallet.shortString()}")
        }
    }

    @Command(name = "switch", description = ["Switch the current context wallet"])
    fun switchWallet(
        @Parameters(description = ["The wallet alias"])
        alias: String
    ) {
        val wallet = getWalletFromAlias(alias)
        cliService.putContextWallet(wallet)
        cliService.putContextDid(wallet.dids.lastOrNull())
        cliService.putContextConnection(wallet.connections.lastOrNull { it.state == ConnectionState.ACTIVE })
    }
}
