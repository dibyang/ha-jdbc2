package net.sf.hajdbc.state.health;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Assert;
import org.junit.Test;

public class TimeoutUtilTest
{
	@Test
	public void callableTimeoutUsesProvidedTimeUnit()
	{
		TimeoutUtil util = new TimeoutUtil("TimeoutUtilTest", 1);

		try
		{
			String value = util.call(new Callable<String>()
			{
				@Override
				public String call() throws Exception
				{
					Thread.sleep(50);
					return "completed";
				}
			}, "timed-out", 1, TimeUnit.SECONDS);

			Assert.assertEquals("completed", value);
		}
		finally
		{
			util.shutdown();
		}
	}

	@Test
	public void timedOutCallableIsCancelled() throws Exception
	{
		TimeoutUtil util = new TimeoutUtil("TimeoutUtilTest", 1);
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch interrupted = new CountDownLatch(1);

		try
		{
			String value = util.call(new Callable<String>()
			{
				@Override
				public String call() throws Exception
				{
					started.countDown();
					try
					{
						Thread.sleep(TimeUnit.SECONDS.toMillis(10));
					}
					catch (InterruptedException e)
					{
						interrupted.countDown();
						throw e;
					}
					return "completed";
				}
			}, "timed-out", 100, TimeUnit.MILLISECONDS);

			Assert.assertEquals("timed-out", value);
			Assert.assertTrue(started.await(1, TimeUnit.SECONDS));
			Assert.assertTrue(interrupted.await(1, TimeUnit.SECONDS));
		}
		finally
		{
			util.shutdown();
		}
	}
}
