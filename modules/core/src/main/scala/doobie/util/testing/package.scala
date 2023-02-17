// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.util

import cats.data.NonEmptyList
import cats.syntax.applicativeError.*
import cats.syntax.foldable.*
import cats.syntax.list.*
import doobie.free.connection.ConnectionIO
import doobie.util.analysis.*
import doobie.util.pretty.*

/**
 * Common utilities for query testing
 */
package object testing {

  def analyze(args: AnalysisArgs): ConnectionIO[AnalysisReport] =
    args.analysis.attempt
      .map(buildItems)
      .map { items =>
        AnalysisReport(
          args.header,
          args.cleanedSql,
          items,
        )
      }

  private def alignmentErrorsToBlock(
    es: NonEmptyList[AlignmentError],
  ): Block =
    Block(es.toList.flatMap(_.msg.linesIterator))

  private def buildItems(
    input: Either[Throwable, Analysis],
  ): List[AnalysisReport.Item] = input match {
    case Left(e) =>
      List(AnalysisReport.Item(
        "SQL Compiles and TypeChecks",
        Some(Block.fromErrorMsgLines(e)),
      ))
    case Right(a) =>
      AnalysisReport.Item("SQL Compiles and TypeChecks", None) ::
      (a.paramDescriptions ++ a.columnDescriptions)
        .map { case (s, es) =>
          AnalysisReport.Item(s, es.toNel.map(alignmentErrorsToBlock))
        }
  }

  /**
   * Simple formatting for analysis results.
   */
  def formatReport(
    args: AnalysisArgs,
    report: AnalysisReport,
    colors: Colors,
  ): Block = {
    val sql = args.cleanedSql
      .wrap(68)
      // SQL should use the default color
      .padLeft(colors.RESET.toString)
    val items = report.items.foldMap(formatItem(colors))
    Block.fromString(args.header)
      .above(sql)
      .above(items)
  }

  private def formatItem(colors: Colors): AnalysisReport.Item => Block = {
    case AnalysisReport.Item(desc, None) =>
      Block.fromString(s"${colors.GREEN}✓${colors.RESET} $desc")
    case AnalysisReport.Item(desc, Some(err)) =>
      Block.fromString(s"${colors.RED}✕${colors.RESET} $desc")
        // No color for error details - ScalaTest paints each line of failure
        // red by default.
        .above(err.wrap(66).padLeft("  "))
  }
}
