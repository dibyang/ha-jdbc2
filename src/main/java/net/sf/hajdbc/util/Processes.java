package net.sf.hajdbc.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.sf.hajdbc.logging.Level;
import net.sf.hajdbc.logging.Logger;
import net.sf.hajdbc.logging.LoggerFactory;

public class Processes
{
	private static final Logger logger = LoggerFactory.getLogger(Processes.class);
	private static final String REDACTED = "[REDACTED]";
	private static final int MAX_COMMAND_SUMMARY_LENGTH = 1024;
	private static final ProcessFactory DEFAULT_PROCESS_FACTORY = new ProcessFactory()
	{
		@Override
		public Process start(ProcessBuilder builder) throws Exception
		{
			return builder.start();
		}
	};

	interface ProcessFactory
	{
		Process start(ProcessBuilder builder) throws Exception;
	}

	public static Map<String, String> environment(final ProcessBuilder builder)
	{
		PrivilegedAction<Map<String, String>> action = new PrivilegedAction<Map<String, String>>()
		{
			@Override
			public Map<String, String> run()
			{
				return builder.environment();
			}
		};
		return AccessController.doPrivileged(action);
	}
	
	public static void run(final ProcessBuilder processBuilder) throws Exception
	{
		run(processBuilder, null);
	}

	public static void run(final ProcessBuilder processBuilder, final File input) throws Exception
	{
		run(processBuilder, input, DEFAULT_PROCESS_FACTORY);
	}

	static void run(final ProcessBuilder processBuilder, final File input, final ProcessFactory processFactory) throws Exception
	{
		processBuilder.redirectErrorStream(true);

		final String commandSummary = commandSummary(processBuilder.command());
		final String executable = processBuilder.command().get(0);
		logger.log(Level.DEBUG, commandSummary);
		
		PrivilegedExceptionAction<Process> action = new PrivilegedExceptionAction<Process>()
		{
			@Override
			public Process run() throws Exception
			{
				Process process = processFactory.start(processBuilder);
				if (input != null)
				{
					PrintWriter writer = new PrintWriter(process.getOutputStream());
					BufferedReader reader = new BufferedReader(new FileReader(input));
					try
					{
						String line = reader.readLine();
						while (line != null)
						{
							writer.println(line);
							line = reader.readLine();
						}
					}
					finally
					{
						reader.close();
					}
				}
				return process;
			}
		};

		Process process;
		try
		{
			process = AccessController.doPrivileged(action);
		}
		catch (PrivilegedActionException e)
		{
			throw new Exception(commandSummary, e.getException());
		}

		try
		{
			int status = process.waitFor();
			
			BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
			long outputLines = 0;
			String line = reader.readLine();
			while (line != null)
			{
				++outputLines;
				line = reader.readLine();
			}
			logger.log(Level.DEBUG, "{0} returned {1}; output-lines={2}", executable, status, outputLines);
			
			if (status != 0)
			{
				throw new Exception(String.format("%s; return-code=%d", commandSummary, status));
			}
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			
			throw new Exception(commandSummary + "; interrupted", e);
		}
	}

	static List<String> redactCommand(List<String> command)
	{
		List<String> redacted = new ArrayList<String>(command.size());
		boolean redactNext = false;

		for (String token: command)
		{
			if (redactNext)
			{
				redacted.add(REDACTED);
				redactNext = false;
				continue;
			}

			redactNext = false;
			String lower = token.toLowerCase(Locale.ROOT);
			if (lower.equals("--password"))
			{
				redacted.add(token);
				redactNext = true;
			}
			else if (lower.startsWith("--password="))
			{
				redacted.add(token.substring(0, token.indexOf('=') + 1) + REDACTED);
			}
			else if (token.startsWith("-p") && (token.length() > 2))
			{
				redacted.add("-p" + REDACTED);
			}
			else
			{
				redacted.add(token);
			}
		}

		return redacted;
	}

	static String commandSummary(List<String> command)
	{
		String summary = Strings.join(redactCommand(command), " ");
		if (summary.length() <= MAX_COMMAND_SUMMARY_LENGTH)
		{
			return summary;
		}

		return summary.substring(0, MAX_COMMAND_SUMMARY_LENGTH - 3) + "...";
	}

	private Processes()
	{
		// Hide
	}
}
