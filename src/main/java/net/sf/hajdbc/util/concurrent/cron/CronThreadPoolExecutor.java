/*
 * HA-JDBC: High-Availability JDBC
 * Copyright (C) 2012  Paul Ferraro
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package net.sf.hajdbc.util.concurrent.cron;

import java.util.Date;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Scheduled thread-pool executor implementation that leverages a Quartz CronExpression to calculate future execution times for scheduled tasks.
 *
 * @author  Paul Ferraro
 * @since   1.1
 */
public class CronThreadPoolExecutor extends ScheduledThreadPoolExecutor implements CronExecutorService
{
	/**
	 * Constructs a new CronThreadPoolExecutor.
	 * @param corePoolSize
	 */
	public CronThreadPoolExecutor(int corePoolSize)
	{
		super(corePoolSize);
	}
	
	/**
	 * Constructs a new CronThreadPoolExecutor.
	 * @param corePoolSize
	 */
	public CronThreadPoolExecutor(int corePoolSize, ThreadFactory threadFactory)
	{
		super(corePoolSize, threadFactory);
	}

	/**
	 * Constructs a new CronThreadPoolExecutor.
	 * @param corePoolSize
	 */
	public CronThreadPoolExecutor(int corePoolSize, RejectedExecutionHandler handler)
	{
		super(corePoolSize, handler);
	}

	/**
	 * Constructs a new CronThreadPoolExecutor.
	 * @param corePoolSize
	 * @param handler
	 */
	public CronThreadPoolExecutor(int corePoolSize, ThreadFactory threadFactory, RejectedExecutionHandler handler)
	{
		super(corePoolSize, threadFactory, handler);
	}

	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.util.concurrent.cron.CronExecutorService#schedule(java.lang.Runnable, net.sf.hajdbc.util.concurrent.cron.CronExpression)
	 */
	@Override
	public void schedule(final Runnable task, final CronExpression expression)
	{
		if (task == null) throw new NullPointerException();
		
		this.setCorePoolSize(this.getCorePoolSize() + 1);
		
		Runnable scheduleTask = new Runnable()
		{
			/**
			 * @see java.lang.Runnable#run()
			 */
			@Override
			public void run()
			{
				Date now = new Date();
				Date time = expression.getNextValidTimeAfter(now);
			
				try
				{
					while (time != null)
					{
						CronThreadPoolExecutor.this.schedule(task, time.getTime() - now.getTime(), TimeUnit.MILLISECONDS);
						
						while (now.before(time))
						{
							Thread.sleep(time.getTime() - now.getTime());
							
							now = new Date();
						}
						
						time = expression.getNextValidTimeAfter(now);
					}
				}
				catch (RejectedExecutionException e)
				{
					// Occurs if executor was already shutdown when schedule() is called
				}
				catch (CancellationException e)
				{
					// Occurs when scheduled, but not yet executed tasks are canceled during shutdown
				}
				catch (InterruptedException e)
				{
					// Occurs when executing tasks are interrupted during shutdownNow()
					Thread.currentThread().interrupt();
				}
			}
		};
		
		this.execute(scheduleTask);
	}
}
