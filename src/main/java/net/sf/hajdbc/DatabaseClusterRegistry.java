package net.sf.hajdbc;

import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import net.sf.hajdbc.logging.Logger;
import net.sf.hajdbc.logging.LoggerFactory;
import net.sf.hajdbc.sql.DatabaseClusterFactoryImpl;
import net.sf.hajdbc.sql.DriverDatabase;
import net.sf.hajdbc.sql.DriverDatabaseClusterConfiguration;
import net.sf.hajdbc.util.TimePeriod;
import net.sf.hajdbc.util.concurrent.LifecycleRegistry;
import net.sf.hajdbc.util.concurrent.MapRegistryStoreFactory;
import net.sf.hajdbc.util.concurrent.Registry;
import net.sf.hajdbc.xml.XMLDatabaseClusterConfigurationFactory;

/**
 * 注册管理器
 * @author dib
 *
 */
public enum DatabaseClusterRegistry {
	registry;
	private static final String CONFIG = "config";
	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory.getLogger(DatabaseClusterRegistry.class);

	volatile TimePeriod timeout = new TimePeriod(10, TimeUnit.SECONDS);
	
	volatile DatabaseClusterFactory<java.sql.Driver, DriverDatabase> factory = new DatabaseClusterFactoryImpl<java.sql.Driver, DriverDatabase>();
	
	final Map<String, DatabaseClusterConfigurationFactory<java.sql.Driver, DriverDatabase>> configurationFactories = new ConcurrentHashMap<String, DatabaseClusterConfigurationFactory<java.sql.Driver, DriverDatabase>>();
	private final Registry.Factory<String, DatabaseCluster<java.sql.Driver, DriverDatabase>, Properties, SQLException> registryFactory = new Registry.Factory<String, DatabaseCluster<java.sql.Driver, DriverDatabase>, Properties, SQLException>()
	{
		@Override
		public DatabaseCluster<java.sql.Driver, DriverDatabase> create(String id, Properties properties) throws SQLException
		{
			DatabaseClusterConfigurationFactory<java.sql.Driver, DriverDatabase> configurationFactory = configurationFactories.get(id);
			
			if (configurationFactory == null)
			{
				String config = (properties != null) ? properties.getProperty(CONFIG) : null;
				configurationFactory = new XMLDatabaseClusterConfigurationFactory<java.sql.Driver, DriverDatabase>(DriverDatabaseClusterConfiguration.class, id, config);
			}
			
			return factory.createDatabaseCluster(id, configurationFactory);
		}

		@Override
		public TimePeriod getTimeout()
		{
			return timeout;
		}
	};
	private final Registry<String, DatabaseCluster<java.sql.Driver, DriverDatabase>, Properties, SQLException> reg = new LifecycleRegistry<String, DatabaseCluster<java.sql.Driver, DriverDatabase>, Properties, SQLException>(registryFactory, new MapRegistryStoreFactory<String>(), ExceptionType.SQL.<SQLException>getExceptionFactory());

	
	public DatabaseCluster<java.sql.Driver, DriverDatabase> get(String id,Properties context) throws SQLException
	{
		return reg.get(id,context);
	}
	
	public DatabaseCluster<java.sql.Driver, DriverDatabase> get(String id) throws SQLException
	{
		return reg.get(id);
	}

	public void stop(String id) throws SQLException
	{
		reg.remove(id);
	}

	public void setFactory(DatabaseClusterFactory<java.sql.Driver, DriverDatabase> databaseClusterFactory)
	{
		factory = databaseClusterFactory;
	}

	public void setConfigurationFactory(String id, DatabaseClusterConfigurationFactory<java.sql.Driver, DriverDatabase> configurationFactory)
	{
		configurationFactories.put(id,  configurationFactory);
	}
	
	public void setTimeout(long value, TimeUnit unit)
	{
		timeout = new TimePeriod(value, unit);
	}

	public DatabaseClusterFactory<java.sql.Driver, DriverDatabase> getFactory() {
		return factory;
	}
	
    

}
