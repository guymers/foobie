package zio.internal.metrics

import zio.Unsafe

object MetricRegistryExposed {

  val snapshot = {
    Unsafe.unsafe(implicit u => metricRegistry.snapshot())
  }
}
