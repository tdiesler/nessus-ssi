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

import org.fusesource.jansi.AnsiConsole
import org.jline.console.CmdLine
import org.jline.console.impl.SystemRegistryImpl
import org.jline.console.impl.SystemRegistryImpl.UnknownCommandException
import org.jline.keymap.KeyMap
import org.jline.reader.Binding
import org.jline.reader.EndOfFileException
import org.jline.reader.LineReader
import org.jline.reader.LineReaderBuilder
import org.jline.reader.Reference
import org.jline.reader.UserInterruptException
import org.jline.reader.impl.DefaultParser
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import org.jline.widget.TailTipWidgets
import org.nessus.didcomm.cli.cmd.AgentCommands
import org.nessus.didcomm.cli.cmd.ClearScreenCommand
import org.nessus.didcomm.cli.cmd.RFC0023Commands
import org.nessus.didcomm.cli.cmd.RFC0048TrustPingCommand
import org.nessus.didcomm.cli.cmd.RFC0095BasicMessageCommand
import org.nessus.didcomm.cli.cmd.RFC0434Commands
import org.nessus.didcomm.cli.cmd.ShowCommands
import org.nessus.didcomm.cli.cmd.WalletCommands
import org.nessus.didcomm.protocol.MessageExchange.Companion.WALLET_ATTACHMENT_KEY
import org.nessus.didcomm.service.ServiceMatrixLoader
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.ExecutionException
import picocli.CommandLine.ParameterException
import picocli.shell.jline3.PicocliCommands
import kotlin.system.exitProcess

@Command(
    name = "didcomm", description = ["Nessus DIDComm-V2"], version = ["1.0"],
    mixinStandardHelpOptions = false,
    usageHelpWidth = 160,
    subcommands = [
        AgentCommands::class,
        ClearScreenCommand::class,
        RFC0023Commands::class,
        RFC0048TrustPingCommand::class,
        RFC0095BasicMessageCommand::class,
        RFC0434Commands::class,
        ShowCommands::class,
        WalletCommands::class,
    ]
)
class NessusCli {

    companion object {

        @JvmStatic
        fun main(args: Array<String>) {
            ServiceMatrixLoader.loadServiceDefinitions()
            exitProcess(NessusCli().runTerminal())
        }

        val defaultCommandLine
            get() = CommandLine(NessusCli())
    }

    val cliService get() = CLIService.getService()

    fun execute(args: String, cmdLine: CommandLine? = null): Result<Any> {
        val cmdln = cmdLine ?: defaultCommandLine
        val toks = smartSplit(args).toTypedArray()
        val parseResult = runCatching { cmdln.parseArgs(*toks) }
        parseResult.onFailure {
            val ex = parseResult.exceptionOrNull() as ParameterException
            cmdln.parameterExceptionHandler.handleParseException(ex, toks)
            return parseResult
        }
        val execResult = runCatching {
            cmdln.executionStrategy.execute(parseResult.getOrNull())
        }
        execResult.onFailure {
            val ex = execResult.exceptionOrNull() as ExecutionException
            cmdln.executionExceptionHandler.handleExecutionException(ex, cmdln, parseResult.getOrNull())
        }
        return execResult
    }

    // Private ---------------------------------------------------------------------------------------------------------

    internal class CommandsFactory(val terminal: Terminal) : CommandLine.IFactory {

        @Suppress("UNCHECKED_CAST")
        override fun <K> create(clazz: Class<K>): K {
            return when(clazz) {
                ClearScreenCommand::class.java -> ClearScreenCommand(terminal) as K
                else -> CommandLine.defaultFactory().create(clazz) as K
            }
        }
    }

    private fun runTerminal(): Int {
        AnsiConsole.systemInstall()
        try {
            TerminalBuilder.builder().build().use { terminal ->

                // Set up picocli commands
                val factory = CommandsFactory(terminal)
                val cmdln = CommandLine(NessusCli(), factory)
                val commands = object : PicocliCommands(cmdln) {
                    override fun name(): String {
                        return "Commands"
                    }
                }

                val topSpec = cmdln.commandSpec
                println(topSpec.usageMessage().description()[0])
                println("Version: ${topSpec.version()[0]}")

                val parser = DefaultParser()
                val systemRegistry = SystemRegistryImpl(parser, terminal, null, null)
                systemRegistry.setCommandRegistries(commands)

                val reader = LineReaderBuilder.builder()
                    .variable(LineReader.LIST_MAX, 50) // max tab completion candidates
                    .completer(systemRegistry.completer())
                    .terminal(terminal)
                    .parser(parser)
                    .build()

                val commandDescription = { cl: CmdLine -> systemRegistry.commandDescription(cl) }
                TailTipWidgets(reader, commandDescription, 5, TailTipWidgets.TipType.COMPLETER).disable()
                val keyMap = reader.keyMaps["main"] as KeyMap<Binding>
                keyMap.bind(Reference("tailtip-toggle"), KeyMap.alt("s"))

                fun prompt(): String {
                    val wallet = cliService.getAttachment(WALLET_ATTACHMENT_KEY)
                    return wallet?.run { "${name}>> " } ?: ">> "
                }

                // Start the shell and process input until the user quits with Ctrl-D
                while (true) {
                    try {
                        systemRegistry.cleanUp()
                        val line = reader.readLine("\n${prompt()}")
                        systemRegistry.execute(line)
                    } catch (e: Exception) {
                        when(e) {
                            is UnknownCommandException -> {}
                            is UserInterruptException -> {}
                            is EndOfFileException -> return 0
                        }
                    }
                }
            }
        } finally {
            AnsiConsole.systemUninstall()
        }
    }

    private fun smartSplit(args: String): List<String> {
        var auxstr: String? = null
        val result = mutableListOf<String>()
        val startQuote = { t: String -> t.startsWith("'") || t.startsWith('"') }
        val endQuote = { t: String -> t.endsWith("'") || t.endsWith('"') }
        args.split(' ').forEach {
            when {
                startQuote(it) -> {
                    auxstr = it.drop(1)
                }
                endQuote(it) -> {
                    result.add("$auxstr $it".dropLast(1))
                    auxstr = null
                }
                else -> {
                    when {
                        auxstr != null -> { auxstr += " $it" }
                        else -> { result.add(it) }
                    }
                }
            }
        }
        return result.toList()
    }
}


