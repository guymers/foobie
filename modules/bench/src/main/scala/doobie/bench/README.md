
`bench/Jmh/run -wi 2 -f 1 -t 2 doobie.bench.select`
```
# JMH version: 1.37
# VM version: JDK 24.0.2, OpenJDK 64-Bit Server VM, 24.0.2
Benchmark                   Mode  Cnt    Score   Error  Units
d.b.s.list_accum_1000_jdbc  avgt    5  184.697 ± 3.700  ns/op
d.b.s.list_accum_1000       avgt    5  225.033 ± 2.429  ns/op
d.b.s.stream_accum_1000     avgt    5  268.893 ± 2.624  ns/op
```

`bench/Jmh/run -wi 3 -f 1 -t 2 doobie.bench.text`
```
# JMH version: 1.37
# VM version: JDK 24.0.2, OpenJDK 64-Bit Server VM, 24.0.2
```

`bench/Jmh/run -wi 500 -i 1500 -f 1 doobie.bench.insert`
```
d.b.i.batch_256     ss  1500  6139.577 ± 854.028  ns/op
d.b.i.batch_512     ss  1500  4093.105 ±  62.703  ns/op
d.b.i.batch_1024    ss  1500  3281.137 ±  45.282  ns/op
d.b.i.batch_2048    ss  1500  2904.488 ±  35.369  ns/op

d.b.i.values_256     ss  1500  4801.105 ± 140.525  ns/op
d.b.i.values_512     ss  1500  3134.149 ±  32.245  ns/op
d.b.i.values_1024    ss  1500  2468.134 ±  60.528  ns/op
d.b.i.values_2048    ss  1500  2515.974 ±  43.229  ns/op

d.b.i.array_256     ss  1500  4015.970 ± 67.103  ns/op
d.b.i.array_512     ss  1500  2521.262 ± 67.015  ns/op
d.b.i.array_1024    ss  1500  1686.773 ± 18.812  ns/op
d.b.i.array_2048    ss  1500  1249.931 ± 10.294  ns/op
```
