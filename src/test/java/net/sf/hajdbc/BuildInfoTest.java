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
package net.sf.hajdbc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.ResourceBundle;

import org.junit.Test;

public class BuildInfoTest
{
	@Test
	public void staticBuildIdentityIsAvailableAtRuntime()
	{
		ResourceBundle buildInfo = ResourceBundle.getBundle("net.sf.hajdbc.BuildInfo");

		assertEquals("1", buildInfo.getString("capabilityVersion"));
		assertEquals(Version.CURRENT.toString(), buildInfo.getString("buildVersion"));
		assertFalse(buildInfo.getString("buildHash").isEmpty());
		assertEquals("", buildInfo.getString("supportedFeatures"));
		assertEquals("", buildInfo.getString("candidateAliasProfiles"));
	}
}
