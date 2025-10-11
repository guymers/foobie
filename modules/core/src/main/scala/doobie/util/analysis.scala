// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.util

import cats.data.Ior
import cats.data.NonEmptyList
import cats.syntax.eq.*
import cats.syntax.foldable.*
import cats.syntax.show.*
import cats.syntax.unorderedFoldable.*
import doobie.enumerated.JdbcType
import doobie.enumerated.Nullability
import doobie.enumerated.Nullability.*
import doobie.enumerated.ParameterMode
import doobie.util.pretty.*

/**
 * Module defining a type for analyzing the type alignment of prepared
 * statements.
 */
object analysis {

  /** Metadata for the JDBC end of a column/parameter mapping. */
  final case class ColumnMeta(
    jdbcType: JdbcType,
    vendorTypeName: String,
    nullability: Nullability,
    name: String,
  )

  /** Metadata for the JDBC end of a column/parameter mapping. */
  final case class ParameterMeta(
    jdbcType: JdbcType,
    vendorTypeName: String,
    nullability: Nullability,
    mode: ParameterMode,
  )

  sealed trait AlignmentError extends Product with Serializable {
    def tag: String
    def index: Int
    def msg: String
  }

  final case class ParameterMisalignment(index: Int, alignment: Option[ParameterMeta]) extends AlignmentError {
    val tag = "P"
    override def msg = this match {
      case ParameterMisalignment(_, None) =>
        show"""|Interpolated value has no corresponding SQL parameter and likely appears inside a
          |comment or quoted string. Ior.Left will result in a runtime failure; fix this by removing
          |the parameter."""
          .stripMargin.linesIterator.mkString(" ")
      case ParameterMisalignment(_, Some(pm)) =>
        show"""|${pm.jdbcType} parameter is not set; this will result in a runtime
          |failure. Perhaps you used a literal ? rather than an interpolated value."""
          .stripMargin.linesIterator.mkString(" ")
    }
  }

  final case class ParameterTypeError(
    index: Int,
    put: Put[?],
    n: NullabilityKnown,
    jdbcType: JdbcType,
    vendorTypeName: String,
  ) extends AlignmentError {
    override val tag = "P"
    override def msg =
      show"""|${typeName(put.typeStack.last, n)} is not coercible to ${jdbcType}
        |(${vendorTypeName})
        |according to the JDBC specification.
        |Expected schema type was ${put.jdbcTargets.head}."""
        .stripMargin.linesIterator.mkString(" ")
  }

  final case class ColumnMisalignment(
    index: Int,
    alignment: Either[(Get[?], NullabilityKnown), ColumnMeta],
  ) extends AlignmentError {
    override val tag = "C"
    override def msg = this match {
      case ColumnMisalignment(_, Left((get, n))) =>
        show"""|Too few columns are selected, which will result in a runtime failure. Add a column or
          |remove mapped ${typeName(get.typeStack.last, n)} from the result type."""
          .stripMargin.linesIterator.mkString(" ")
      case ColumnMisalignment(_, Right(_)) =>
        show"""Column is unused. Remove it from the SELECT statement."""
    }
  }

  final case class NullabilityMisalignment(
    index: Int,
    name: String,
    st: Option[String],
    jdk: NullabilityKnown,
    jdbc: NullabilityKnown,
  ) extends AlignmentError {
    override val tag = "C"
    override def msg = this match {
      // https://github.com/tpolecat/doobie/issues/164 ... NoNulls means "maybe no nulls"  :-\
      // case NullabilityMisalignment(i, name, st, NoNulls, Nullable) =>
      //   s"""Non-nullable column ${name.toUpperCase} is unnecessarily mapped to an Option type."""
      case NullabilityMisalignment(_, _, st, Nullable, NoNulls) =>
        show"""|Reading a NULL value into ${typeName(st, NoNulls)} will result in a runtime failure.
          |Fix this by making the schema type ${formatNullability(NoNulls)} or by changing the
          |Scala type to ${typeName(st, Nullable)}""".stripMargin.linesIterator.mkString(" ")
      case _ => sys.error("unpossible, evidently")
    }
  }

  final case class ColumnTypeError(
    index: Int,
    get: Get[?],
    n: NullabilityKnown,
    schema: ColumnMeta,
  ) extends AlignmentError {
    override val tag = "C"
    override def msg =
      show"""|${schema.jdbcType} (${schema.vendorTypeName}) is not
        |coercible to ${typeName(get.typeStack.last, n)} according to the JDBC specification or any defined
        |mapping.
        |Fix this by changing the schema type to
        |${get.jdbcSources.mkString_(" or ")}; or the
        |Scala type to an appropriate ${if (schema.jdbcType === JdbcType.Array) "array" else "object"}
        |type.
        |""".stripMargin.linesIterator.mkString(" ")
  }

  final case class ColumnTypeWarning(
    index: Int,
    get: Get[?],
    n: NullabilityKnown,
    schema: ColumnMeta,
  ) extends AlignmentError {
    override val tag = "C"
    override def msg =
      show"""|${schema.jdbcType} (${schema.vendorTypeName}) is ostensibly
        |coercible to ${typeName(get.typeStack.last, n)}
        |according to the JDBC specification but is not a recommended target type.
        |Expected schema type was
        |${get.jdbcSources.mkString_(" or ")}.
        |""".stripMargin.linesIterator.mkString(" ")
  }

  /** Compatibility analysis for the given statement and aligned mappings. */
  final case class Analysis(
    driver: String,
    sqlKeywords: Set[String],
    sql: String,
    parameterAlignment: List[Ior[(Put[?], NullabilityKnown), ParameterMeta]],
    columnAlignment: List[Ior[(Get[?], NullabilityKnown), ColumnMeta]],
  ) {

    // spanner does not have a UUID column but the driver allows a UUID object and will convert it to a string.
    // So if the database is spanner allow a UUID in and out of a varchar.

    private val parameterAlignment_ = {
      val tweaked = parameterAlignment.map(_.map { m =>
        m.copy(jdbcType = tweakMetaJdbcType(driver, m.jdbcType, vendorTypeName = m.vendorTypeName))
      })

      if (Analysis.isPostgresSpanner(driver, sqlKeywords)) {
        tweaked.map {
          case Ior.Both((p, n), m) if isPostgresPutUUID(p) && isSpannerVarchar(m.jdbcType, m.vendorTypeName) =>
            Ior.Both((p, n), m.copy(jdbcType = JdbcType.Other, vendorTypeName = "uuid"))
          case v => v
        }
      } else tweaked
    }
    private val columnAlignment_ = {
      val tweaked = columnAlignment.map(_.map { m =>
        m.copy(jdbcType = tweakMetaJdbcType(driver, m.jdbcType, vendorTypeName = m.vendorTypeName))
      })

      if (Analysis.isPostgresSpanner(driver, sqlKeywords)) {
        tweaked.map {
          case Ior.Both((p, n), m) if isPostgresGetUUID(p) && isSpannerVarchar(m.jdbcType, m.vendorTypeName) =>
            Ior.Both((p, n), m.copy(jdbcType = JdbcType.Other, vendorTypeName = "uuid"))
          case v => v
        }
      } else tweaked
    }

    private def isPostgresGetUUID(g: Get[?]) = g match {
      case _: Get.Basic[?] => false
      case g: Get.Advanced[?] => g.jdbcSources.contains_(JdbcType.Other) && g.schemaTypes == NonEmptyList.of("uuid")
    }

    private def isPostgresPutUUID(p: Put[?]) = p match {
      case _: Put.Basic[?] => false
      case p: Put.Advanced[?] => p.jdbcTargets.contains_(JdbcType.Other) && p.schemaTypes == NonEmptyList.of("uuid")
    }

    private def isSpannerVarchar(jdbcType: JdbcType, vendorTypeName: String) =
      jdbcType === JdbcType.VarChar && vendorTypeName == "varchar"

    def parameterMisalignments: List[ParameterMisalignment] =
      parameterAlignment_.zipWithIndex.collect {
        case (Ior.Left(_), n) => ParameterMisalignment(n + 1, None)
        case (Ior.Right(p), n) => ParameterMisalignment(n + 1, Some(p))
      }

    def parameterTypeErrors: List[ParameterTypeError] =
      parameterAlignment_.zipWithIndex.collect {
        case (Ior.Both((j, n1), p), n) if !j.jdbcTargets.contains_(p.jdbcType) =>
          ParameterTypeError(n + 1, j, n1, p.jdbcType, p.vendorTypeName)
      }

    def columnMisalignments: List[ColumnMisalignment] =
      columnAlignment_.zipWithIndex.collect {
        case (Ior.Left(j), n) => ColumnMisalignment(n + 1, Left(j))
        case (Ior.Right(p), n) => ColumnMisalignment(n + 1, Right(p))
      }

    def columnTypeErrors: List[ColumnTypeError] =
      columnAlignment_.zipWithIndex.collect {
        case (Ior.Both((j, n1), p), n)
            if !(j.jdbcSources.toList ++ j.fold(_.jdbcSourceSecondary, _ => Nil)).contains_(p.jdbcType) =>
          ColumnTypeError(n + 1, j, n1, p)
        case (Ior.Both((j, n1), p), n)
            if (p.jdbcType === JdbcType.JavaObject || p.jdbcType === JdbcType.Other) && !j.fold(
              _ => None,
              a => Some(a.schemaTypes.head),
            ).contains_(p.vendorTypeName) =>
          ColumnTypeError(n + 1, j, n1, p)
      }

    def columnTypeWarnings: List[ColumnTypeWarning] =
      columnAlignment_.zipWithIndex.collect {
        case (Ior.Both((j, n1), p), n) if j.fold(_.jdbcSourceSecondary, _ => Nil).contains_(p.jdbcType) =>
          ColumnTypeWarning(n + 1, j, n1, p)
      }

    def nullabilityMisalignments: List[NullabilityMisalignment] =
      columnAlignment_.zipWithIndex.collect {
        // We can't do anything helpful with NoNulls .. it means "might not be nullable"
        // case (Ior.Both((st, Nullable), ColumnMeta(_, _, NoNulls, col)), n) => NullabilityMisalignment(n + 1, col, st, NoNulls, Nullable)
        case (Ior.Both((st, NoNulls), ColumnMeta(_, _, Nullable, col)), n) =>
          NullabilityMisalignment(n + 1, col, st.typeStack.last, Nullable, NoNulls)
        // N.B. if we had a warning mechanism we could issue a warning for NullableUnknown
      }

    lazy val parameterAlignmentErrors =
      parameterMisalignments ++ parameterTypeErrors

    lazy val columnAlignmentErrors =
      columnMisalignments ++ columnTypeErrors ++ columnTypeWarnings ++ nullabilityMisalignments

    lazy val alignmentErrors =
      (parameterAlignmentErrors).sortBy(m => (m.index, m.msg)) ++
        (columnAlignmentErrors).sortBy(m => (m.index, m.msg))

    /** Description of each parameter, paired with its errors. */
    lazy val paramDescriptions: List[(String, List[AlignmentError])] = {
      val params: Block =
        parameterAlignment_.zipWithIndex.map {
          case (Ior.Both((j1, n1), ParameterMeta(j2, s2, _, _)), i) =>
            List(f"P${i + 1}%02d", show"${typeName(j1.typeStack.last, n1)}", " → ", j2.show, show"($s2)")
          case (Ior.Left((j1, n1)), i) =>
            List(f"P${i + 1}%02d", show"${typeName(j1.typeStack.last, n1)}", " → ", "", "")
          case (Ior.Right(ParameterMeta(j2, s2, _, _)), i) =>
            List(f"P${i + 1}%02d", "", " → ", j2.show, show"($s2)")
        }.transpose.map(Block(_)).foldLeft(Block(Nil))(_.leftOf1(_)).trimLeft(1)
      params.toString.linesIterator.toList.zipWithIndex.map { case (show, n) =>
        (show, parameterAlignmentErrors.filter(_.index == n + 1))
      }
    }

    /** Description of each parameter, paired with its errors. */
    lazy val columnDescriptions: List[(String, List[AlignmentError])] = {
      import pretty.*
      val cols: Block =
        columnAlignment_.zipWithIndex.map {
          case (Ior.Both((j1, n1), ColumnMeta(j2, s2, n2, m)), i) => List(
              f"C${i + 1}%02d",
              m,
              j2.show,
              show"(${s2})",
              formatNullability(n2),
              " → ",
              typeName(j1.typeStack.last, n1),
            )
          case (Ior.Left((j1, n1)), i) =>
            List(f"C${i + 1}%02d", "", "", "", "", " → ", typeName(j1.typeStack.last, n1))
          case (Ior.Right(ColumnMeta(j2, s2, n2, m)), i) =>
            List(f"C${i + 1}%02d", m, j2.show, show"(${s2})", formatNullability(n2), " → ", "")
        }.transpose.map(Block(_)).foldLeft(Block(Nil))(_.leftOf1(_)).trimLeft(1)
      cols.toString.linesIterator.toList.zipWithIndex.map { case (show, n) =>
        (show, columnAlignmentErrors.filter(_.index == n + 1))
      }
    }

  }
  object Analysis {

    private val MySQLDriverName = "MySQL Connector/J"
    private val PostgresDriverName = "PostgreSQL JDBC Driver"

    private[doobie] def isMySQL(driver: String) = driver == MySQLDriverName
    private[doobie] def isPostgres(driver: String) = driver == PostgresDriverName
    private[doobie] def isPostgresSpanner(driver: String, sqlKeywords: Set[String]) = isPostgres(driver) && !sqlKeywords.contains("unlogged")
  }

  // Some stringy helpers

  private val packagePrefix = "\\b[a-z]+\\.".r

  private def typeName(t: Option[String], n: NullabilityKnown): String = {
    val name = packagePrefix.replaceAllIn(t.fold("«erased»")(identity(_)), "")
    n match {
      case NoNulls => name
      case Nullable => show"Option[${name}]"
    }
  }

  private def formatNullability(n: Nullability): String =
    n match {
      case NoNulls => "NOT NULL"
      case Nullable => "NULL"
      case NullableUnknown => "NULL?"
    }

  // tweaks to the types returned by JDBC to improve analysis
  private def tweakMetaJdbcType(driver: String, jdbcType: JdbcType, vendorTypeName: String) = jdbcType match {
    // the Postgres driver does not return *WithTimezone JDBC types for *tz column types
    // https://github.com/pgjdbc/pgjdbc/issues/2485
    // https://github.com/pgjdbc/pgjdbc/issues/1766
    case JdbcType.Time if vendorTypeName.compareToIgnoreCase("timetz") == 0 => JdbcType.TimeWithTimezone
    case JdbcType.Timestamp if vendorTypeName.compareToIgnoreCase("timestamptz") == 0 => JdbcType.TimestampWithTimezone

    // MySQL timestamp columns are returned as Timestamp
    case JdbcType.Timestamp if vendorTypeName.compareToIgnoreCase("timestamp") == 0 && Analysis.isMySQL(driver) =>
      JdbcType.TimestampWithTimezone

    case t => t
  }
}
