package doobie.mysql.util.arbitraries

import org.scalacheck.Arbitrary

import java.sql.Date
import java.sql.Time
import java.sql.Timestamp

object SQLArbitraries {

  implicit val arbitraryTime: Arbitrary[Time] = Arbitrary {
    TimeArbitraries.arbitraryLocalTime.arbitrary.map(Time.valueOf(_))
  }

  implicit val arbitraryDate: Arbitrary[Date] = Arbitrary {
    TimeArbitraries.arbitraryLocalDate.arbitrary.map(Date.valueOf(_))
  }

  implicit val arbitraryTimestamp: Arbitrary[Timestamp] = Arbitrary {
    TimeArbitraries.arbitraryLocalDateTime.arbitrary.map(Timestamp.valueOf(_))
  }

}
