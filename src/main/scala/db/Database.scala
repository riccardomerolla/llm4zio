package db
import javax.sql.DataSource

import zio.*

import org.sqlite.SQLiteDataSource

object Database:

  val live: ZLayer[DatabaseConfig, PersistenceError, DataSource] =
    ZLayer.scoped {
      for
        config <- ZIO.service[DatabaseConfig]
        tuple  <- ZIO.acquireRelease(
                    ZIO.attemptBlocking {
                      Class.forName("org.sqlite.JDBC")
                      val ds     = new SQLiteDataSource()
                      ds.setUrl(config.jdbcUrl)
                      val anchor = ds.getConnection
                      val pragma = anchor.createStatement()
                      try pragma.execute("PRAGMA foreign_keys = ON")
                      finally pragma.close()
                      (ds: DataSource, anchor)
                    }.mapError(e => PersistenceError.ConnectionFailed(e.getMessage))
                  ) {
                    case (_, anchor) =>
                      ZIO.attemptBlocking(anchor.close()).ignore
                  }
      yield tuple._1
    }
