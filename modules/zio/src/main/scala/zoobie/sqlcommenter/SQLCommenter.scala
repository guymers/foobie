package zoobie.sqlcommenter

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import scala.collection.immutable.SortedMap
import scala.jdk.CollectionConverters.*

// https://google.github.io/sqlcommenter/spec/
final case class SQLCommenter(
  controller: Option[String],
  action: Option[String],
  framework: Option[String],
  trace: Option[SQLCommenter.Trace],
) {
  import SQLCommenter.serializeKeyValues

  def format: String = {
    val traceState = trace.flatMap(_.state).map { state =>
      state
        .filter { case (k, _) => k.nonEmpty }
        .map { case (k, v) => s"$k=$v" }
        .mkString(",")
    }
    val m = SortedMap(
      "controller" -> controller,
      "action" -> action,
      "framework" -> framework,
      "traceparent" -> trace.map(_.parent),
      "tracestate" -> traceState,
    ).collect { case (k, Some(v)) => (k, v) }
    serializeKeyValues(m)
  }

}
object SQLCommenter {

  final case class Trace(
    traceId: String,
    spanId: String,
    options: Byte,
    state: Option[Map[String, String]],
  ) {
    def parent = String.format("00-%s-%s-%02X", traceId, spanId, options)
  }
  object Trace {

    def fromOpenTelemetryContext(spanContext: io.opentelemetry.api.trace.SpanContext) = {
      Option(spanContext).filter(_.isValid).map { ctx =>
        val traceId = ctx.getTraceId
        val spanId = ctx.getSpanId
        val options = ctx.getTraceFlags

        val state = Option(ctx.getTraceState).filter(!_.isEmpty).map { state =>
          state.asMap().asScala.toMap
        }

        Trace(traceId = traceId, spanId = spanId, options.asByte, state)
      }
    }
  }

  private[sqlcommenter] val serializeKey =
    urlEncode andThen escapeMetaCharacters
  private[sqlcommenter] val serializeValue =
    urlEncode andThen escapeMetaCharacters andThen sqlEscape

  private[sqlcommenter] def serializeKeyValue(k: String, v: String) = s"${serializeKey(k)}=${serializeValue(v)}"

  private[sqlcommenter] def serializeKeyValues(m: Map[String, String]) = {
    if (m.isEmpty) ""
    else m.toList.sorted.map(serializeKeyValue.tupled).mkString(",")
  }

  @SuppressWarnings(Array("org.wartremover.warts.Null"))
  private def urlEncode(s: String) = {
    URLEncoder.encode(s, StandardCharsets.UTF_8)
      .replaceAll("%27", "'")
      .replaceAll("\\+", "%20")
  }
  private def escapeMetaCharacters(s: String) = s.replaceAll("'", "\\\\'")
  private def sqlEscape(s: String) = s"'$s'"

  def affix(state: SQLCommenter, sql: String): String = {
    val commentStr = state.format
    if (commentStr.isEmpty) sql else sql.concat(s"\n/*${commentStr}*/")
  }
}
