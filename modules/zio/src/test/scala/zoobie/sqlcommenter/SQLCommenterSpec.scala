package zoobie.sqlcommenter

import zio.test.ZIOSpecDefault
import zio.test.assertTrue

object SQLCommenterSpec extends ZIOSpecDefault {
  import SQLCommenter.*

  override val spec = suite("SQLCommenter")(
    suite("serialization")(
      test("key") {
        assertTrue(serializeKey("1234") == "1234") &&
        assertTrue(serializeKey("route parameter") == "route%20parameter") &&
        assertTrue(serializeKey("FOO 'BAR") == "FOO%20\\'BAR")
      },
      test("value") {
        assertTrue(serializeValue("1234") == "'1234'") &&
        assertTrue(serializeValue("/param first") == "'%2Fparam%20first'") &&
        assertTrue(serializeValue("FOO 'BAR") == "'FOO%20\\'BAR'") &&
        assertTrue(serializeValue("DROP TABLE FOO") == "'DROP%20TABLE%20FOO'")
      },
      test("key value") {
        assertTrue(serializeKeyValue("route", "/polls 1000") == "route='%2Fpolls%201000'")
      },
      test("key values") {
        val in = Map(
          "route" -> "/param*d",
          "controller" -> "index",
          "traceparent" -> "00-5bd66ef5095369c7b0d1f8f4bd33716a-c532cb4098ac3dd2-01",
          "tracestate" -> "congo=t61rcWkgMzE,rojo=00f067aa0ba902b7",
        )
        assertTrue(serializeKeyValues(in) == "controller='index',route='%2Fparam*d',traceparent='00-5bd66ef5095369c7b0d1f8f4bd33716a-c532cb4098ac3dd2-01',tracestate='congo%3Dt61rcWkgMzE%2Crojo%3D00f067aa0ba902b7'")
      },
      suite("affix")(
        test("empty") {
          val state = SQLCommenter(
            controller = None,
            action = None,
            framework = None,
            trace = None,
          )
          assertTrue(affix(state, "SELECT * FROM foo") == "SELECT * FROM foo")
        },
        test("not empty") {
          val state = SQLCommenter(
            controller = None,
            action = Some("/param*d"),
            framework = None,
            trace = None,
          )
          assertTrue(affix(state, "SELECT * FROM foo") == "SELECT * FROM foo\n/*action='%2Fparam*d'*/")
        },
        test("trace") {
          val state = SQLCommenter(
            controller = None,
            action = None,
            framework = None,
            trace = Some(SQLCommenter.Trace(
              traceId = "5bd66ef5095369c7b0d1f8f4bd33716a",
              spanId = "c532cb4098ac3dd2",
              options = 0,
              state = Some(Map("congo" -> "t61rcWkgMzE", "rojo" -> "00f067aa0ba902b7")),
            )),
          )
          assertTrue(
            affix(
              state,
              "SELECT * FROM foo",
            ) == """SELECT * FROM foo
              |/*traceparent='00-5bd66ef5095369c7b0d1f8f4bd33716a-c532cb4098ac3dd2-00',tracestate='congo%3Dt61rcWkgMzE%2Crojo%3D00f067aa0ba902b7'*/""".stripMargin,
          )
        },
      ),
    ),
  )
}
