/*
 * Copyright (C) 2013 Brett Wooldridge
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.zaxxer.hikari.pool;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import com.zaxxer.hikari.proxy.ConnectionState;

/**
 * Mediator pattern interfaces.
 *
 * @author Brett Wooldridge
 */
public interface Mediators
{
   JdbcMediator getJdbcMediator();

   PoolEntryMediator getConnectionStateMediator();

   PoolMediator getPoolMediator();

   interface JdbcMediator
   {
      /**
       * Check whether the connection is alive or not.
       *
       * @param connection the connection to test
       * @return true if the connection is alive, false if it is not alive or we timed out
       */
      boolean isConnectionAlive(Connection connection);

      Throwable getLastConnectionFailure();

      /**
       * Close connection and eat any exception.
       *
       * @param connection the connection to close
       * @param closureReason the reason the connection was closed (if known)
       */
      void quietlyCloseConnection(Connection connection, String closureReason);

      /**
       * Get the raw DataSource that the pool is managing.
       *
       * @return the underlying DataSource
       */
      DataSource getUnwrappedDataSource();
   }

   interface PoolMediator
   {
      void registerMBeans();

      void unregisterMBeans();

      void shutdownTimeoutExecutor();
   }

   interface PoolEntryMediator
   {
      PoolEntry newPoolEntry() throws Exception;
      
      void resetConnectionState(Connection connection, ConnectionState liveState, int dirtyBits) throws SQLException;
   }
}
