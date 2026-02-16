
`bench/Jmh/run -wi 2 -i 2 -f 1 doobie.bench.select`
```
# JMH version: 1.37
# VM version: JDK 21.0.8, OpenJDK 64-Bit Server VM
Benchmark                   Mode  Cnt    Score   Error  Units
d.b.s.list_accum_1000_jdbc  avgt    2  172.809          ns/op
d.b.s.list_accum_1000       avgt    2  209.440          ns/op
d.b.s.stream_accum_1000     avgt    2  250.966          ns/op
```

`bench/Jmh/run -wi 500 -i 1500 -f 1 doobie.bench.insert`
```
# JMH version: 1.37
# VM version: JDK 21.0.8, OpenJDK 64-Bit Server VM
Benchmark          Mode   Cnt     Score     Error  Units
d.b.i.array_512      ss  1500  2637.671 ±  74.508  ns/op
d.b.i.array_1024     ss  1500  1536.033 ±  17.970  ns/op
d.b.i.array_2048     ss  1500  1115.185 ±  20.325  ns/op
d.b.i.batch_512      ss  1500  3894.445 ±  42.689  ns/op
d.b.i.batch_1024     ss  1500  3040.211 ±  21.192  ns/op
d.b.i.batch_2048     ss  1500  2648.115 ±  19.796  ns/op
d.b.i.values_512     ss  1500  3057.419 ±  34.115  ns/op
d.b.i.values_1024    ss  1500  2360.481 ±  41.843  ns/op
d.b.i.values_2048    ss  1500  2343.947 ± 116.611  ns/op
```
