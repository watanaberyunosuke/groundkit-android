"""Runs the Dive's historyQ (dives/airport_conditions/index.tsx) on HKG's flights at a fixed
`now`, so the Kotlin port (FlightHistory) can be checked against the original SQL."""
import duckdb, json, sys
src, out = sys.argv[1], sys.argv[2]
con = duckdb.connect()
con.sql("set TimeZone='UTC'")
icao, tz = "VHHH", "Asia/Hong_Kong"
con.sql(f"copy (select * from '{src}/marts.fct_arrivals.parquet' where arrival_icao = '{icao}') to '{out}/arrivals_vhhh.parquet' (format parquet, compression zstd)")
con.sql(f"copy (select * from '{src}/marts.fct_departures.parquet' where departure_icao = '{icao}') to '{out}/departures_vhhh.parquet' (format parquet, compression zstd)")
now = con.sql(f"select max(arrived_at) + interval 6 hour from '{out}/arrivals_vhhh.parquet'").fetchone()[0]
NOW = f"timestamptz '{now.isoformat()}'"
rows = con.sql(f"""
    with seen as (
      select callsign, 'inbound' as dir, coalesce(departure_iata, departure_icao) as other, arrived_at as seen_at
      from '{out}/arrivals_vhhh.parquet'
      where arrival_icao = '{icao}' and arrived_at >= {NOW} - interval 30 day and callsign is not null
      union all
      select callsign, 'outbound', coalesce(arrival_iata, arrival_icao), departed_at
      from '{out}/departures_vhhh.parquet'
      where departure_icao = '{icao}' and departed_at >= {NOW} - interval 30 day and callsign is not null
    ),
    timed as (
      select *,
        hour(seen_at at time zone '{tz}') * 60 + minute(seen_at at time zone '{tz}') as m,
        arg_min(hour(seen_at at time zone '{tz}') * 60 + minute(seen_at at time zone '{tz}'), seen_at)
          over (partition by callsign, dir) as ref
      from seen
    ),
    modes as (
      select callsign, dir, other, count(*) c from seen where other is not null group by all
    ),
    unique_mode as (
      select callsign, dir, count(*) filter (where c = mx) = 1 as mode_is_unique
      from (select *, max(c) over (partition by callsign, dir) mx from modes) group by all
    )
    select
      t.callsign, t.dir, mode(other) as other, count(*) as n,
      (((any_value(ref) + median(((((m - ref + 720) % 1440) + 1440) % 1440) - 720)) % 1440) + 1440) % 1440
        as usual_min,
      count(distinct cast(seen_at at time zone '{tz}' as date)) filter (where seen_at >= {NOW} - interval 14 day)
        as days_14,
      coalesce(any_value(u.mode_is_unique), true) as mode_is_unique
    from timed t left join unique_mode u using (callsign, dir)
    group by t.callsign, t.dir
""").fetchall()
epoch_ms = int(now.timestamp() * 1000)
json.dump({"now_ms": epoch_ms, "icao": icao, "timezone": tz,
           "rows": [dict(zip(["callsign", "dir", "other", "n", "usual_min", "days_14", "mode_is_unique"], r)) for r in rows]},
          open(f"{out}/history_vhhh.expected.json", "w"), default=float)
print(len(rows), "callsign/direction rows; now =", now)
