package net.sf.hajdbc.state.simple;

import net.sf.hajdbc.MockDatabase;

import org.junit.Assert;
import org.junit.Test;

public class SimpleStateManagerTest
{
	@Test
	public void isValidDoesNotRequireActiveDatabase()
	{
		SimpleStateManager manager = new SimpleStateManager();

		Assert.assertFalse(manager.getActiveDatabases().contains("db1"));
		Assert.assertTrue(manager.isValid(new MockDatabase("db1")));
	}
}
