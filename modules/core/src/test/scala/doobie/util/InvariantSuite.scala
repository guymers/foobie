// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.util

import zio.test.ZIOSpecDefault
import zio.test.assertTrue

object InvariantSuite extends ZIOSpecDefault {

  override val spec = suite("Invariant")(
    test("NonNullableColumnRead should include a one-based indexing disclaimer") {
      val ex = invariant.NonNullableColumnRead(1, doobie.enumerated.JdbcType.Array)
      assertTrue(ex.getMessage.contains("is 1-based"))
    },
  )
}
