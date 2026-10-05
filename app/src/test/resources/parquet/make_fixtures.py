"""Writes synthetic Parquet fixtures plus <file>.expected.json (DuckDB's reading of every
file in the fixture directory) for the Kotlin Parquet reader's unit tests."""
import duckdb, json, glob, os, sys
out = sys.argv[1]
con = duckdb.connect()
con.sql("set TimeZone='UTC'")
con.sql("""
create table t as
select
  i::integer                                         as i32,
  case when i % 7 = 0 then null else i * 1000003 end::bigint as i64,
  (i / 3.0)::float                                   as f,
  case when i % 5 = 0 then null else i * 1.25 end::double     as d,
  case when i % 4 = 0 then null when i % 3 = 0 then 'Hong Kong 香港' else 'QF' || (i % 37) end as s,
  case when i % 6 = 0 then null else i % 2 = 0 end   as b,
  timestamp '2026-10-01 00:00:00' + to_seconds(i * 61) + to_microseconds(i)   as ts,
  timestamptz '2026-10-01 00:00:00+00' + to_minutes(i)                        as tstz,
  (timestamp '2026-10-01 00:00:00' + to_seconds(i))::timestamp_ms             as ts_ms,
  (timestamp '2026-10-01 00:00:00' + to_seconds(i))::timestamp_ns             as ts_ns,
  date '2026-01-01' + (i % 300)::integer             as dt,
  (i * 1.001)::decimal(10, 3)                        as dec,
  (i * 7)::decimal(20, 2)                            as bigdec,
  repeat('x', i % 50)                                as long_s,
  'flight-' || i::varchar || '-' || (i * 7919 % 1000)::varchar as uniq_s
from range(1200) r(i)
""")
for comp in ["zstd", "snappy", "gzip", "uncompressed"]:
    for ver in ["V1", "V2"]:
        con.sql(f"copy t to '{out}/synthetic_{comp}_{ver.lower()}.parquet' "
                f"(format parquet, compression {comp}, parquet_version {ver}, row_group_size 500)")
con.sql(f"copy (select i::integer as i, case when i % 3 = 0 then null else 'r' || (i % 11) end as s "
        f"from range(5000) r(i)) to '{out}/multi_rowgroup.parquet' (format parquet, compression zstd, row_group_size 2048)")
for f in sorted(glob.glob(f"{out}/*.parquet")):
    cols = con.sql(f"describe select * from '{f}'").fetchall()
    exprs = []
    for name, typ, *_ in cols:
        q = f'"{name}"'
        if typ.startswith("TIMESTAMP"): exprs.append(f"epoch_ms({q})")
        elif typ == "DATE": exprs.append(f"({q} - date '1970-01-01')")
        elif typ.startswith("DECIMAL"): exprs.append(f"{q}::double")
        else: exprs.append(q)
    rows = con.sql(f"select {', '.join(exprs)} from '{f}'").fetchall()
    with open(f + ".expected.json", "w") as fh:
        json.dump({"columns": [[c[0], c[1]] for c in cols], "rows": rows}, fh, ensure_ascii=False)
    print(os.path.basename(f), len(rows), "rows")
