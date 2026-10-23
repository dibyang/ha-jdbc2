package net.sf.hajdbc.distributed.jgroups;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.jgroups.JChannel;
import org.jgroups.Message;
import org.jgroups.ReceiverAdapter;
import org.jgroups.protocols.TCP_NIO2;
import org.jgroups.protocols.TCPPING;
import org.jgroups.protocols.pbcast.GMS;
import org.jgroups.stack.IpAddress;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real loopback sockets and the packaged protocol stacks, without touching site ports. */
public class DeferredBindingChannelTest
{
	@Test(timeout = 90000)
	public void bothStacksJoinExchangeAndReconnectWithEnabledMixedAndRollback() throws Exception
	{
		for (String stack : new String[] { "lock_tcp.xml", "state_tcp.xml" })
		{
			for (boolean[] modes : new boolean[][] { { true, true }, { true, false }, { false, false } })
			{
				int firstPort = freePort(), secondPort;
				do { secondPort = freePort(); } while (firstPort == secondPort);
				TCP_NIO2 firstTransport = null, secondTransport = null;
				try (JChannel first = create(stack, firstPort, firstPort, secondPort, modes[0]);
					 JChannel second = create(stack, secondPort, firstPort, secondPort, modes[1]))
				{
					firstTransport = (TCP_NIO2) first.getProtocolStack().findProtocol(TCP_NIO2.class);
					secondTransport = (TCP_NIO2) second.getProtocolStack().findProtocol(TCP_NIO2.class);
					String group = "defer-test-" + UUID.randomUUID();
					first.connect(group);
					second.connect(group);
					awaitPair(first, second);
					exchange(first, second);
					second.disconnect();
					second.connect(group);
					awaitPair(first, second);
					exchange(first, second);
					assertEquals(firstPort, ((IpAddress) first.down(new org.jgroups.Event(
						org.jgroups.Event.GET_PHYSICAL_ADDRESS, first.getAddress()))).getPort());
				}
				assertFalse(firstTransport.isSelectorOpen());
				assertFalse(secondTransport.isSelectorOpen());
				long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
				while ((firstTransport.isAcceptorRunning() || secondTransport.isAcceptorRunning())
					&& System.nanoTime() < deadline) Thread.sleep(10);
				assertFalse(firstTransport.isAcceptorRunning());
				assertFalse(secondTransport.isAcceptorRunning());
			}
		}
	}

	private static JChannel create(String stack, int port, int a, int b, boolean deferred) throws Exception
	{
		JChannel channel = new JChannel(stack);
		try
		{
			TCP_NIO2 tcp = (TCP_NIO2) channel.getProtocolStack().findProtocol(TCP_NIO2.class);
			tcp.setValue("bind_addr", InetAddress.getByName("127.0.0.1"));
			tcp.setValue("bind_port", port);
			tcp.setValue("defer_client_bind_addr", deferred);
			channel.getProtocolStack().findProtocol(TCPPING.class).setValue("initial_hosts",
				Arrays.asList(new IpAddress("127.0.0.1", a), new IpAddress("127.0.0.1", b)));
			channel.getProtocolStack().findProtocol(GMS.class).setValue("join_timeout", 500L);
			OutboundBindingConfiguration.validateAndLog(stack, channel);
			return channel;
		}
		catch (Exception | Error failure) { channel.close(); throw failure; }
	}

	private static int freePort() throws Exception
	{
		try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")))
		{ return socket.getLocalPort(); }
	}

	private static void awaitPair(JChannel a, JChannel b) throws Exception
	{
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() < deadline)
		{
			if (a.getView().size() == 2 && b.getView().size() == 2) return;
			Thread.sleep(10);
		}
		fail("Both members must join within five seconds");
	}

	private static void exchange(JChannel a, JChannel b) throws Exception
	{
		CountDownLatch messages = new CountDownLatch(2);
		ReceiverAdapter receiver = new ReceiverAdapter() {
			@Override public void receive(Message message) {
				if ("binding-test".equals(message.getObject())) messages.countDown();
			}
		};
		a.setReceiver(receiver);
		b.setReceiver(receiver);
		a.send(new Message(b.getAddress(), "binding-test"));
		b.send(new Message(a.getAddress(), "binding-test"));
		assertTrue("Bidirectional messages", messages.await(5, TimeUnit.SECONDS));
	}
}
