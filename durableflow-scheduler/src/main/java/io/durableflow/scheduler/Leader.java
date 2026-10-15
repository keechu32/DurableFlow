package io.durableflow.scheduler;

import java.sql.*;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

@Component
public final class Leader implements AutoCloseable {
  private static final long KEY = 741938251L;
  private final DataSource source;
  private Connection connection;

  public Leader(DataSource source) {
    this.source = source;
  }

  public synchronized boolean elected() {
    try {
      if (connection != null) {
        try (var statement = connection.createStatement();
            var result = statement.executeQuery("SELECT 1")) {
          return result.next();
        }
      }
      Connection candidate = source.getConnection();
      try {
        try (var statement = candidate.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
          statement.setQueryTimeout(2);
          statement.setLong(1, KEY);
          try (var result = statement.executeQuery()) {
            if (result.next() && result.getBoolean(1)) {
              connection = candidate;
              return true;
            }
          }
        }
        return false;
      } finally {
        if (connection != candidate) candidate.close();
      }
    } catch (SQLException ex) {
      release();
      return false;
    }
  }

  private void release() {
    if (connection == null) return;
    try (var statement = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
      statement.setLong(1, KEY);
      statement.execute();
    } catch (SQLException ex) {
      try {
        connection.abort(Runnable::run);
      } catch (SQLException ignored) {
        /* Broken physical connection will be evicted by Hikari. */
      }
    } finally {
      try {
        connection.close();
      } catch (SQLException ignored) {
        /* Connection already broken. */
      }
      connection = null;
    }
  }

  @Override
  public synchronized void close() {
    release();
  }
}
