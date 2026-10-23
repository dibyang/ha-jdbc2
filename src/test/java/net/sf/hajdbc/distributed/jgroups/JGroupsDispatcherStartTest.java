package net.sf.hajdbc.distributed.jgroups;

import org.jgroups.Event;
import org.jgroups.JChannel;
import org.jgroups.protocols.CENTRAL_LOCK;
import org.jgroups.protocols.Locking;
import org.jgroups.protocols.TCP_NIO2;
import org.jgroups.stack.ProtocolStack;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** 启动失败关闭与重试边界；不启动网络线程。 */
public class JGroupsDispatcherStartTest
{
	private JChannel channel()
	{
		JChannel channel = mock(JChannel.class);
		ProtocolStack stack = mock(ProtocolStack.class);
		when(channel.getProtocolStack()).thenReturn(stack);
		when(stack.getTransport()).thenReturn(mock(TCP_NIO2.class));
		when(stack.findProtocol(Locking.class)).thenReturn(new CENTRAL_LOCK());
		return channel;
	}

	@Test
	public void finalFailureClosesBothChannelKindsAndPreservesLastCause() throws Exception
	{
		for (String id : new String[] { "test.lock", "test.state" })
		{
			JChannel channel = channel();
			Exception first = new Exception("first"), last = new Exception("last");
			doThrow(first).doThrow(first).doThrow(first).doThrow(first).doThrow(last)
				.when(channel).connect(id, null, 2000);
			try { new JGroupsCommandDispatcher<>(id, channel, 1000, null, null, null).start(); fail(); }
			catch (Exception actual) { assertSame(last, actual); }
			verify(channel, times(5)).connect(id, null, 2000);
			verify(channel).close();
		}
	}

	@Test
	public void cleanupFailureIsSuppressedAndSelfSuppressionIsAvoided() throws Exception
	{
		for (boolean same : new boolean[] { false, true })
		{
			JChannel channel = channel();
			RuntimeException primary = new IllegalStateException("connect");
			RuntimeException cleanup = same ? primary : new IllegalStateException("close");
			doThrow(primary).when(channel).connect("test.lock", null, 2000);
			doThrow(cleanup).when(channel).close();
			try { new JGroupsCommandDispatcher<>("test.lock", channel, 1000, null, null, null).start(); fail(); }
			catch (RuntimeException actual)
			{
				assertSame(primary, actual);
				assertEquals(same ? 0 : 1, actual.getSuppressed().length);
				if (!same) assertSame(cleanup, actual.getSuppressed()[0]);
			}
			verify(channel).close();
		}
	}

	@Test
	public void errorClosesImmediatelyWithoutRetryOrTypeChange() throws Exception
	{
		JChannel channel = channel();
		Error failure = new AssertionError("startup");
		doThrow(failure).when(channel).connect("test.state", null, 2000);
		try { new JGroupsCommandDispatcher<>("test.state", channel, 1000, null, null, null).start(); fail(); }
		catch (Error actual) { assertSame(failure, actual); }
		verify(channel).connect("test.state", null, 2000);
		verify(channel).close();
	}

	@Test
	public void successOnLastAttemptKeepsChannelOpen() throws Exception
	{
		JChannel channel = channel();
		Exception failure = new Exception("retry");
		doThrow(failure).doThrow(failure).doThrow(failure).doThrow(failure).doNothing()
			.when(channel).connect("test.lock", null, 2000);
		new JGroupsCommandDispatcher<>("test.lock", channel, 1000, null, null, null).start();
		verify(channel, times(5)).connect("test.lock", null, 2000);
		verify(channel, never()).close();
	}

	@Test
	public void diagnosticsFailureIsNotSwallowedOrRetriedAndClosesChannel() throws Exception
	{
		JChannel channel = channel();
		when(channel.getProtocolStack().findProtocol(TCP_NIO2.class)).thenReturn(new TCP_NIO2());
		JGroupsCommandDispatcher<Object> dispatcher = new JGroupsCommandDispatcher<>("test.state", channel, 1000, null, null, null);
		RuntimeException failure = new IllegalStateException("physical address lookup");
		when(channel.down(any(Event.class))).thenThrow(failure);
		try { dispatcher.start(); fail(); }
		catch (RuntimeException actual) { assertSame(failure, actual); }
		verify(channel).connect("test.state", null, 2000);
		verify(channel).close();
	}
}
