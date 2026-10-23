package net.sf.hajdbc.distributed.jgroups;

import java.net.InetAddress;
import java.net.SocketAddress;
import java.nio.channels.SocketChannel;
import java.io.IOException;
import org.jgroups.JChannel;
import org.jgroups.blocks.cs.NioBaseServer;
import org.jgroups.blocks.cs.NioConnection;
import org.jgroups.protocols.TCP_NIO2;
import org.jgroups.stack.IpAddress;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class OutboundBindingConfigurationTest
{
	private static final String PROPERTY = "ha.jdbc.jgroups.defer_client_bind_addr";

	@Test
	public void factoryRoutesBothChannelsAndRejectsEffectiveSystemPropertyConflict() throws Exception
	{
		String previous = System.getProperty(PROPERTY);
		String previousAddress = System.getProperty(org.jgroups.Global.TCP_CLIENT_BIND_ADDR);
		try
		{
			System.setProperty(org.jgroups.Global.TCP_CLIENT_BIND_ADDR, "127.0.0.1");
			JGroupsCommandDispatcherFactory factory = new JGroupsCommandDispatcherFactory();
			factory.setStack("tcp.xml");
			for (String id : new String[] { "test.lock", "test.state" })
			{
				System.setProperty(PROPERTY, "true");
				try { factory.createCommandDispatcher(id, null, null, null); fail("Expected conflict"); }
				catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains(id)); }
				System.setProperty(PROPERTY, "false");
				factory.createCommandDispatcher(id, null, null, null).stop();
			}
		}
		finally
		{
			if (previous == null) System.clearProperty(PROPERTY); else System.setProperty(PROPERTY, previous);
			if (previousAddress == null) System.clearProperty(org.jgroups.Global.TCP_CLIENT_BIND_ADDR);
			else System.setProperty(org.jgroups.Global.TCP_CLIENT_BIND_ADDR, previousAddress);
		}
	}

	@Test
	public void bothPackagedStacksResolveDefaultEnabledAndRollback() throws Exception
	{
		String previous = System.getProperty(PROPERTY);
		try
		{
			for (String value : new String[] { null, "false", "true", "false" })
			{
				if (value == null) System.clearProperty(PROPERTY); else System.setProperty(PROPERTY, value);
				for (String stack : new String[] { "lock_tcp.xml", "state_tcp.xml" })
				{
					try (JChannel channel = new JChannel(stack))
					{
						TCP_NIO2 tcp = (TCP_NIO2) channel.getProtocolStack().findProtocol(TCP_NIO2.class);
						assertEquals(Boolean.valueOf(value), tcp.getValue("defer_client_bind_addr"));
						assertEquals(stack.startsWith("lock") ? 7800 : 7801, tcp.getValue("bind_port"));
						OutboundBindingConfiguration.validateAndLog(stack, channel);
					}
				}
			}
		}
		finally { if (previous == null) System.clearProperty(PROPERTY); else System.setProperty(PROPERTY, previous); }
	}

	@Test
	public void fixedOutboundBindingIsRejectedOnlyWhenDeferred() throws Exception
	{
		try (JChannel channel = new JChannel("lock_tcp.xml"))
		{
			TCP_NIO2 tcp = (TCP_NIO2) channel.getProtocolStack().findProtocol(TCP_NIO2.class);
			for (String property : new String[] { "client_bind_addr", "client_bind_port" })
			{
				tcp.setValue(property, property.endsWith("addr") ? InetAddress.getLoopbackAddress() : 12345);
				tcp.setValue("defer_client_bind_addr", false);
				OutboundBindingConfiguration.validateAndLog("test.lock", channel);
				tcp.setValue("defer_client_bind_addr", true);
				try { OutboundBindingConfiguration.validateAndLog("test.lock", channel); fail("Expected conflict"); }
				catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("test.lock")); }
				tcp.setValue(property, property.endsWith("addr") ? null : 0);
			}
		}
	}

	@Test
	public void actualJGroupsConnectSkipsBindOnlyWhenEnabledAndClosesOnFailure() throws Exception
	{
		for (boolean deferred : new boolean[] { false, true, false })
		{
			NioBaseServer server = mock(NioBaseServer.class);
			when(server.deferClientBinding()).thenReturn(deferred);
			when(server.sendBufferSize()).thenReturn(8192);
			when(server.receiveBufferSize()).thenReturn(8192);
			when(server.maxSendBuffers()).thenReturn(10);
			IOException failure = new IOException("test connect failure");
			RecordingSocketChannel socket = new RecordingSocketChannel(failure);
			IpAddress peer = new IpAddress("127.0.0.1", 7801);
			try (ObservedConnection connection = new ObservedConnection(peer, server, socket))
			{
				try { connection.connect(peer); fail("Expected connect failure"); }
				catch (IOException expected) { assertSame(failure, expected); }
				assertEquals(deferred ? 0 : 1, socket.bindCalls);
				assertEquals(1, socket.connectCalls);
				assertFalse(socket.isOpen());
				assertTrue(socket.closed);
			}
		}
	}

	/** A real channel lifecycle, unlike Mockito's uninitialised final close() machinery. */
	private static final class RecordingSocketChannel extends SocketChannel
	{
		final IOException failure;
		int bindCalls, connectCalls;
		boolean closed;
		RecordingSocketChannel(IOException failure) { super(java.nio.channels.spi.SelectorProvider.provider()); this.failure = failure; }
		@Override public SocketChannel bind(SocketAddress address) { bindCalls++; return this; }
		@Override public boolean connect(SocketAddress address) throws IOException { connectCalls++; throw failure; }
		@Override protected void implCloseSelectableChannel() { closed = true; }
		@Override protected void implConfigureBlocking(boolean block) {}
		@Override public SocketAddress getLocalAddress() { return null; }
		@Override public SocketAddress getRemoteAddress() { return null; }
		@Override public java.net.Socket socket() { throw new UnsupportedOperationException(); }
		@Override public boolean isConnected() { return false; }
		@Override public boolean isConnectionPending() { return false; }
		@Override public boolean finishConnect() { return false; }
		@Override public SocketChannel shutdownInput() { return this; }
		@Override public SocketChannel shutdownOutput() { return this; }
		@Override public <T> SocketChannel setOption(java.net.SocketOption<T> option, T value) { return this; }
		@Override public <T> T getOption(java.net.SocketOption<T> option) { return null; }
		@Override public java.util.Set<java.net.SocketOption<?>> supportedOptions() { return java.util.Collections.emptySet(); }
		@Override public int read(java.nio.ByteBuffer target) { return -1; }
		@Override public long read(java.nio.ByteBuffer[] target, int offset, int length) { return -1; }
		@Override public int write(java.nio.ByteBuffer source) { return 0; }
		@Override public long write(java.nio.ByteBuffer[] source, int offset, int length) { return 0; }
	}

	/** Replace only the test socket; inherited connect is the dependency's real implementation. */
	private static final class ObservedConnection extends NioConnection
	{
		ObservedConnection(IpAddress peer, NioBaseServer server, SocketChannel socket) throws Exception
		{
			super(peer, server);
			this.channel.close();
			this.channel = socket;
		}
	}
}
