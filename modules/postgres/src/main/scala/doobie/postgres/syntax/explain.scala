// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.postgres.syntax

import cats.syntax.apply.*
import doobie.free.connection.ConnectionIO
import doobie.hi.HC
import doobie.hi.HPS
import doobie.hi.HRS
import doobie.util.query.Query
import doobie.util.query.Query0
import doobie.util.update.Update
import doobie.util.update.Update0

import scala.language.implicitConversions

trait ToPostgresExplainOps {
  implicit def toPostgresExplainQuery0Ops(q: Query0[?]): PostgresExplainQuery0Ops =
    new PostgresExplainQuery0Ops(q)

  implicit def toPostgresExplainQueryOps[A](q: Query[A, ?]): PostgresExplainQueryOps[A] =
    new PostgresExplainQueryOps(q)

  implicit def toPostgresExplainUpdate0Ops(u: Update0): PostgresExplainUpdate0Ops =
    new PostgresExplainUpdate0Ops(u)

  implicit def toPostgresExplainUpdateOps[A](u: Update[A]): PostgresExplainUpdateOps[A] =
    new PostgresExplainUpdateOps(u)
}

class PostgresExplainQuery0Ops(self: Query0[?]) {

  /**
   * Construct a program in [[ConnectionIO]] which returns the server's query
   * plan for the query (i.e., `EXPLAIN` output). The query is not actually
   * executed.
   */
  def explain: ConnectionIO[List[String]] =
    self.inspect { (sql, prepare) =>
      HC.prepareStatement(s"EXPLAIN $sql")(prepare *> HPS.executeQuery(HRS.build[List, String]))
    }

  /**
   * Construct a program in [[ConnectionIO]] which returns the server's query
   * plan for the query, with a comparison to the actual execution (i.e.,
   * `EXPLAIN ANALYZE` output). The query will be executed, but no results are
   * returned.
   */
  def explainAnalyze: ConnectionIO[List[String]] =
    self.inspect { (sql, prepare) =>
      HC.prepareStatement(s"EXPLAIN ANALYZE $sql")(prepare *> HPS.executeQuery(HRS.build[List, String]))
    }
}

class PostgresExplainQueryOps[A](self: Query[A, ?]) {

  /**
   * Apply the argument `a` to construct a program in [[ConnectionIO]] which
   * returns the server's query plan for the query (i.e., `EXPLAIN` output). The
   * query is not actually executed.
   */
  def explain(a: A): ConnectionIO[List[String]] = {
    self.inspect(a) { (sql, prepare) =>
      HC.prepareStatement(s"EXPLAIN $sql")(prepare *> HPS.executeQuery(HRS.build[List, String]))
    }
  }

  /**
   * Apply the argument `a` to construct a program in [[ConnectionIO]] which
   * returns the server's query plan for the query, with a comparison to the
   * actual execution (i.e., `EXPLAIN ANALYZE` output). The query will be
   * executed, but no results are returned.
   */
  def explainAnalyze(a: A): ConnectionIO[List[String]] =
    self.inspect(a) { (sql, prepare) =>
      HC.prepareStatement(s"EXPLAIN ANALYZE $sql")(prepare *> HPS.executeQuery(HRS.build[List, String]))
    }
}

class PostgresExplainUpdate0Ops(self: Update0) {

  /**
   * Construct a program in [[ConnectionIO]] which returns the server's query
   * plan for the query (i.e., `EXPLAIN` output). The query is not actually
   * executed.
   */
  def explain: ConnectionIO[List[String]] =
    self.inspect { (sql, prepare) =>
      HC.prepareStatement(s"EXPLAIN $sql")(prepare *> HPS.executeQuery(HRS.build[List, String]))
    }

  /**
   * Construct a program in [[ConnectionIO]] which returns the server's query
   * plan for the query, with a comparison to the actual execution (i.e.,
   * `EXPLAIN ANALYZE` output). The query will be executed, but no results are
   * returned.
   */
  def explainAnalyze: ConnectionIO[List[String]] =
    self.inspect { (sql, prepare) =>
      HC.prepareStatement(s"EXPLAIN ANALYZE $sql")(prepare *> HPS.executeQuery(HRS.build[List, String]))
    }
}

class PostgresExplainUpdateOps[A](self: Update[A]) {

  /**
   * Apply the argument `a` to construct a program in [[ConnectionIO]] which
   * returns the server's query plan for the query (i.e., `EXPLAIN` output). The
   * query is not actually executed.
   */
  def explain(a: A): ConnectionIO[List[String]] = {
    self.inspect(a) { (sql, prepare) =>
      HC.prepareStatement(s"EXPLAIN $sql")(prepare *> HPS.executeQuery(HRS.build[List, String]))
    }
  }

  /**
   * Apply the argument `a` to construct a program in [[ConnectionIO]] which
   * returns the server's query plan for the query, with a comparison to the
   * actual execution (i.e., `EXPLAIN ANALYZE` output). The query will be
   * executed, but no results are returned.
   */
  def explainAnalyze(a: A): ConnectionIO[List[String]] =
    self.inspect(a) { (sql, prepare) =>
      HC.prepareStatement(s"EXPLAIN ANALYZE $sql")(prepare *> HPS.executeQuery(HRS.build[List, String]))
    }
}
