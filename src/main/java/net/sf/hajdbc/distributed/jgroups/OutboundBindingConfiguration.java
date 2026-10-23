package net.sf.hajdbc.distributed.jgroups;

import net.sf.hajdbc.logging.Level;
import net.sf.hajdbc.logging.LoggerFactory;
import org.jgroups.JChannel;
import org.jgroups.protocols.TCP_NIO2;

/** Validates effective TCP_NIO2 properties before its server is started. */
final class OutboundBindingConfiguration
{
	private OutboundBindingConfiguration() {}

	static void validateAndLog(String channelName, JChannel channel)
	{
		TCP_NIO2 transport = (TCP_NIO2) channel.getProtocolStack().findProtocol(TCP_NIO2.class);
		if (transport == null) return;
		// JGroups' public property accessor reads the resolved XML/system properties.
		boolean deferred = (Boolean) transport.getValue("defer_client_bind_addr");
		Object address = transport.getValue("client_bind_addr");
		int port = (Integer) transport.getValue("client_bind_port");
		if (deferred && (address != null || port != 0))
		{
			throw new IllegalArgumentException("Channel " + channelName
				+ ": defer_client_bind_addr=true conflicts with client_bind_addr/client_bind_port; "
				+ "remove the fixed outbound binding or disable deferred binding");
		}
		LoggerFactory.getLogger(OutboundBindingConfiguration.class).log(Level.INFO,
			"JGroups channel={0}, configured listen address={1}, port={2}, port_range={3}, "
				+ "defer_client_bind_addr={4}, client_bind_addr={5}, client_bind_port={6}",
			channelName, transport.getValue("bind_addr"), transport.getValue("bind_port"),
			transport.getValue("port_range"), deferred, address, port);
	}
}
