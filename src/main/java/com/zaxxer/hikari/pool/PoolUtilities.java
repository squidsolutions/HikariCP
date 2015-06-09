package com.zaxxer.hikari.pool;

import static com.zaxxer.hikari.util.UtilityElf.createInstance;

import java.lang.management.ManagementFactory;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.util.DefaultThreadFactory;
import com.zaxxer.hikari.util.DriverDataSource;
import com.zaxxer.hikari.util.PropertyBeanSetter;

public final class PoolUtilities
{
   private static final Logger LOGGER = LoggerFactory.getLogger(PoolUtilities.class);

   private Executor netTimeoutExecutor;

   private final HikariConfig config;
   private final String poolName;
   private volatile boolean isValidChecked; 
   private volatile boolean isValidSupported;
   private boolean isNetworkTimeoutSupported;
   private boolean isQueryTimeoutSupported;

   public PoolUtilities(final HikariConfig configuration)
   {
      this.config = configuration;
      this.poolName = configuration.getPoolName();
      this.isValidSupported = true;
      this.isNetworkTimeoutSupported = true;
      this.isQueryTimeoutSupported = true;
   }

   /**
    * Close connection and eat any exception.
    *
    * @param connection the connection to close
    * @param closureReason the reason the connection was closed (if known)
    */
   public void quietlyCloseConnection(final Connection connection, final String closureReason)
   {
      final String addendum = closureReason != null ? " (" + closureReason + ")" : "";
      try {
         if (connection == null || connection.isClosed()) {
            return;
         }

         LOGGER.debug("Closing connection {} in pool {}{}", connection, poolName, addendum);
         try {
            setNetworkTimeout(connection, TimeUnit.SECONDS.toMillis(15));
         }
         finally {
            // continue with the close even if setNetworkTimeout() throws (due to driver poorly behaving drivers)
            connection.close();
         }
      }
      catch (Throwable e) {
         LOGGER.debug("Exception closing connection {} in pool {}{}", connection, poolName, addendum, e);
      }
   }

   /**
    * Create/initialize the underlying DataSource.
    *
    * @return a DataSource instance
    */
   public DataSource initializeDataSource()
   {
      final String jdbcUrl = config.getJdbcUrl();
      final String username = config.getUsername();
      final String password = config.getPassword();
      final String dsClassName = config.getDataSourceClassName();
      final String driverClassName = config.getDriverClassName();
      final Properties dataSourceProperties = config.getDataSourceProperties();

      DataSource dataSource = config.getDataSource();
      if (dsClassName != null && dataSource == null) {
         dataSource = createInstance(dsClassName, DataSource.class);
         PropertyBeanSetter.setTargetFromProperties(dataSource, dataSourceProperties);
      }
      else if (jdbcUrl != null && dataSource == null) {
         dataSource = new DriverDataSource(jdbcUrl, driverClassName, dataSourceProperties, username, password);
      }

      if (dataSource != null) {
         setLoginTimeout(dataSource, config.getConnectionTimeout());
         createNetworkTimeoutExecutor(dataSource, dsClassName, jdbcUrl, Math.min(config.getValidationTimeout(), TimeUnit.SECONDS.toMillis(5)));
      }

      return dataSource;
   }

   /**
    * Setup a connection initial state.
    *
    * @param connection a Connection
    * @param initSql 
    * @param isAutoCommit auto-commit state
    * @param isReadOnly read-only state
    * @param transactionIsolation transaction isolation
    * @param catalog default catalog
    * @throws SQLException thrown from driver
    */
   public void setupConnection(final Connection connection, final String initSql, final boolean isAutoCommit, final boolean isReadOnly, final int transactionIsolation, final String catalog) throws SQLException
   {
      connection.setAutoCommit(isAutoCommit);
      connection.setReadOnly(isReadOnly);
      if (transactionIsolation != connection.getTransactionIsolation()) {
         connection.setTransactionIsolation(transactionIsolation);
      }
      if (catalog != null) {
         connection.setCatalog(catalog);
      }

      executeSql(connection, initSql, isAutoCommit);
   }

   /**
    * Return true if the driver appears to be JDBC 4.0 compliant.
    *
    * @param connection a Connection to check
    * @return true if JDBC 4.1 compliance, false otherwise
    */
   public boolean isJdbc4ValidationSupported(final Connection connection)
   {
      if (!isValidChecked) {
         try {
            // We don't care how long the wait actually is here, just whether it returns without exception. This
            // call will throw various exceptions in the case of a non-JDBC 4.0 compliant driver
            connection.isValid(1);
         }
         catch (Throwable e) {
            isValidSupported = false;
            LOGGER.debug("{} - JDBC4 Connection.isValid() not supported ({})", poolName, e.getMessage());
         }

         isValidChecked = true;
      }
      
      return isValidSupported;
   }

   /**
    * Set the query timeout, if it is supported by the driver.
    *
    * @param statement a statement to set the query timeout on
    * @param timeoutSec the number of seconds before timeout
    */
   public void setQueryTimeout(final Statement statement, final int timeoutSec)
   {
      if (isQueryTimeoutSupported) {
         try {
            statement.setQueryTimeout(timeoutSec);
         }
         catch (Throwable e) {
            isQueryTimeoutSupported = false;
            LOGGER.debug("{} - Statement.setQueryTimeout() not supported", poolName);
         }
      }
   }

   /**
    * Set the network timeout, if <code>isUseNetworkTimeout</code> is <code>true</code> and the
    * driver supports it.  Return the pre-existing value of the network timeout.
    *
    * @param connection the connection to set the network timeout on
    * @param timeoutMs the number of milliseconds before timeout
    * @return the pre-existing network timeout value
    */
   public int getAndSetNetworkTimeout(final Connection connection, final long timeoutMs)
   {
      if (isNetworkTimeoutSupported) {
         try {
            final int networkTimeout = connection.getNetworkTimeout();
            connection.setNetworkTimeout(netTimeoutExecutor, (int) timeoutMs);
            return networkTimeout;
         }
         catch (Throwable e) {
            isNetworkTimeoutSupported = false;
            LOGGER.debug("{} - Connection.setNetworkTimeout() not supported", poolName);
         }
      }

      return 0;
   }

   /**
    * Set the network timeout, if <code>isUseNetworkTimeout</code> is <code>true</code> and the
    * driver supports it.
    *
    * @param connection the connection to set the network timeout on
    * @param timeoutMs the number of milliseconds before timeout
    * @throws SQLException throw if the connection.setNetworkTimeout() call throws
    */
   public void setNetworkTimeout(final Connection connection, final long timeoutMs) throws SQLException
   {
      if (isNetworkTimeoutSupported) {
         connection.setNetworkTimeout(netTimeoutExecutor, (int) timeoutMs);
      }
   }

   /**
    * Register MBeans for HikariConfig and HikariPool.
    *
    * @param configuration a HikariConfig instance
    * @param pool a HikariPool instance
    */
   void registerMBeans(final HikariPool pool)
   {
      if (!config.isRegisterMbeans()) {
         return;
      }

      try {
         final MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();

         final ObjectName beanConfigName = new ObjectName("com.zaxxer.hikari:type=PoolConfig (" + poolName + ")");
         final ObjectName beanPoolName = new ObjectName("com.zaxxer.hikari:type=Pool (" + poolName + ")");
         if (!mBeanServer.isRegistered(beanConfigName)) {
            mBeanServer.registerMBean(config, beanConfigName);
            mBeanServer.registerMBean(pool, beanPoolName);
         }
         else {
            LOGGER.error("You cannot use the same pool name for separate pool instances.");
         }
      }
      catch (Exception e) {
         LOGGER.warn("Unable to register management beans.", e);
      }
   }

   /**
    * Unregister MBeans for HikariConfig and HikariPool.
    *
    * @param configuration a HikariConfig instance
    * @param pool a HikariPool instance
    */
   void unregisterMBeans(final HikariPool pool)
   {
      if (!config.isRegisterMbeans()) {
         return;
      }

      try {
         final MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();

         final ObjectName beanConfigName = new ObjectName("com.zaxxer.hikari:type=PoolConfig (" + poolName + ")");
         final ObjectName beanPoolName = new ObjectName("com.zaxxer.hikari:type=Pool (" + poolName + ")");
         if (mBeanServer.isRegistered(beanConfigName)) {
            mBeanServer.unregisterMBean(beanConfigName);
            mBeanServer.unregisterMBean(beanPoolName);
         }
      }
      catch (Exception e) {
         LOGGER.warn("Unable to unregister management beans.", e);
      }
   }

   /**
    * Execute the user-specified init SQL.
    *
    * @param connection the connection to initialize
    * @param sql the SQL to execute
    * @param isAutoCommit whether to commit the SQL after execution or not
    * @throws SQLException throws if the init SQL execution fails
    */
   private void executeSql(final Connection connection, final String sql, final boolean isAutoCommit) throws SQLException
   {
      if (sql != null) {
         try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
            if (!isAutoCommit) {
               connection.commit();
            }
         }
      }
   }

   // Temporary hack for MySQL issue: http://bugs.mysql.com/bug.php?id=75615
   private void createNetworkTimeoutExecutor(final DataSource dataSource, final String dsClassName, final String jdbcUrl, final long keepAliveMs)
   {
      if ((dsClassName != null && dsClassName.contains("Mysql")) ||
          (jdbcUrl != null && jdbcUrl.contains("mysql")) ||
          (dataSource != null && dataSource.getClass().getName().contains("Mysql"))) {
         netTimeoutExecutor = new SynchronousExecutor();
      }
      else {
         ThreadFactory threadFactory = config.getThreadFactory() != null ? config.getThreadFactory() : new DefaultThreadFactory("Hikari JDBC-timeout executor", true);
         ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newCachedThreadPool(threadFactory);
         executor.allowCoreThreadTimeOut(true);
         executor.setKeepAliveTime(keepAliveMs, TimeUnit.MILLISECONDS);
         netTimeoutExecutor = executor; 
      }
   }

   private static class SynchronousExecutor implements Executor
   {
      /** {@inheritDoc} */
      @Override
      public void execute(Runnable command)
      {
         try {
            command.run();
         }
         catch (Throwable t) {
            LOGGER.debug("Exception executing {}", command, t);
         }
      }
   }

   /**
    * Set the loginTimeout on the specified DataSource.
    *
    * @param dataSource the DataSource
    * @param connectionTimeout the timeout in milliseconds
    */
   private void setLoginTimeout(final DataSource dataSource, final long connectionTimeout)
   {
      if (connectionTimeout != Integer.MAX_VALUE) {
         try {
            dataSource.setLoginTimeout((int) TimeUnit.MILLISECONDS.toSeconds(Math.max(1000L, connectionTimeout)));
         }
         catch (SQLException e) {
            LOGGER.warn("Unable to set DataSource login timeout", e);
         }
      }
   }
}
