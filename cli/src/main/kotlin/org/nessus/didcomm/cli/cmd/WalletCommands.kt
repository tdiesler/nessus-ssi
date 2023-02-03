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
package org.nessus.didcomm.cli.cmd

import org.nessus.didcomm.model.toWallet
import org.nessus.didcomm.protocol.MessageExchange.Companion.WALLET_ATTACHMENT_KEY
import org.nessus.didcomm.wallet.AgentType
import org.nessus.didcomm.wallet.Wallet
import org.nessus.didcomm.wallet.toWalletModel
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import java.util.concurrent.Callable

@Command(
    name = "wallet",
    description = ["Wallet related commands"],
    subcommands = [
        WalletCreateCommand::class,
        WalletRemoveCommand::class,
        WalletSwitchCommand::class,
    ]
)
class WalletCommands: AbstractBaseCommand() {
}

@Command(name = "create", description = ["Create a wallet for a given agent"])
class WalletCreateCommand: AbstractBaseCommand() {

    @Option(names = ["-n", "--name"], required = true, description = ["The wallet name"])
    var name: String? = null

    @Option(names = ["-a", "--agent"], description = ["The agent type (default=Nessus)"], defaultValue = "Nessus")
    var agent: String? = null

    override fun call(): Int {
        val wallet = Wallet.Builder(name!!)
            .agentType(AgentType.fromValue(agent!!))
            .build()
        cliService.putAttachment(WALLET_ATTACHMENT_KEY, wallet)
        println("Wallet created: ${wallet.toWalletModel().asString()}")
        return 0
    }
}

@Command(name = "remove", description = ["Remove and delete a given wallet"])
class WalletRemoveCommand: AbstractBaseCommand() {

    @Option(names = ["--alias"], description = ["The wallet alias"])
    var alias: String? = null

    override fun call(): Int {
        getContextWallet(alias).also {
            walletService.removeWallet(it.id)
            cliService.removeAttachment(WALLET_ATTACHMENT_KEY)
            println("Wallet removed: ${it.asString()}")
        }
        return 0
    }
}

@Command(name = "switch", description = ["Switch the current context wallet"])
class WalletSwitchCommand: AbstractBaseCommand() {

    @Parameters(description = ["The target alias"])
    var alias: String? = null

    override fun call(): Int {
        getWalletModel(alias!!).also {
            cliService.putAttachment(WALLET_ATTACHMENT_KEY, it.toWallet())
        }
        return 0
    }
}


