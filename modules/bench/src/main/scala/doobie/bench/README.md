
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
