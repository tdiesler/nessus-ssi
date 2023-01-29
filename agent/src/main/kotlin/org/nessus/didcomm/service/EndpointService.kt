package org.nessus.didcomm.service

import id.walt.servicematrix.ServiceProvider
import mu.KotlinLogging
import org.nessus.didcomm.protocol.MessageListener
import org.nessus.didcomm.wallet.Wallet


abstract class EndpointService<T: Any>: NessusBaseService() {
    override val implementation get() = serviceImplementation<EndpointService<Any>>()
    override val log = KotlinLogging.logger {}

    companion object: ServiceProvider {
        override fun getService() = object : EndpointService<Any>() {}
        override fun defaultImplementation() = CamelEndpointService()
    }

    /**
     * Starts the endpoint service for a given wallet
     *
     * @return A handle specific to the endpoint implementation
     */
    open fun startEndpoint(endpointUrl: String, listener: MessageListener? = null): T? {
        return null
    }

    /**
     * Stops the endpoint service represented by the given handle
     */
    open fun stopEndpoint(handle: T? = null) {
        // Do nothing
    }
}
