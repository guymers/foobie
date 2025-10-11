
`bench/Jmh/run -wi 2 -i 2 -f 1 doobie.bench.select`
```
# JMH version: 1.37
# VM version: JDK 21.0.8, OpenJDK 64-Bit Server VM
Benchmark                   Mode  Cnt    Score   Error  Units
d.b.s.list_accum_1000_jdbc  avgt    2  172.809          ns/op
d.b.s.list_accum_1000       avgt    2  209.440          ns/op
d.b.s.stream_accum_1000     avgt    2  250.966          ns/op
```

`bench/Jmh/run -wi 2 -i 2 -f 1 doobie.bench.text`
```
# JMH version: 1.37
# VM version: JDK 21.0.8, OpenJDK 64-Bit Server VM
Benchmark              Mode  Cnt     Score   Error  Units
d.b.t.batch            avgt    2  2257.216          ns/op
d.b.t.batch_optimized  avgt    2  2008.081          ns/op
d.b.t.copy_foldable    avgt    2   306.674          ns/op
d.b.t.copy_stream      avgt    2   382.481          ns/op
```

`bench/Jmh/run -wi 500 -i 1500 -f 1 doobie.bench.insert`
```
# JMH version: 1.37
# VM version: JDK 21.0.8, OpenJDK 64-Bit Server VM
Benchmark          Mode   Cnt     Score     Error  Units
d.b.i.array_512      ss  1500  2108.146 ±  29.363  ns/op
d.b.i.array_1024     ss  1500  1282.125 ±  17.193  ns/op
d.b.i.array_2048     ss  1500   901.204 ±  27.426  ns/op
d.b.i.batch_512      ss  1500  3610.360 ±  49.533  ns/op
d.b.i.batch_1024     ss  1500  2824.224 ± 158.971  ns/op
d.b.i.batch_2048     ss  1500  2413.790 ±  15.661  ns/op
d.b.i.values_512     ss  1500  2661.017 ±  70.472  ns/op
d.b.i.values_1024    ss  1500  1930.406 ±  25.466  ns/op
d.b.i.values_2048    ss  1500  1759.005 ±  13.514  ns/op
```
