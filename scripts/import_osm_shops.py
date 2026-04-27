#!/usr/bin/env python3
"""
Import real-world POI data from OpenStreetMap/Overpass into zyro-local's tb_shop.

The script keeps real source attributes where available:
- store name
- coordinates
- partial address / district tags
- opening hours

Fields not present in OSM but required by the project schema are deterministically
enriched so the dataset can be used immediately by the app:
- images
- avg_price
- sold
- comments
- score

It also supports rebuilding Redis GEO indexes after import.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import random
import re
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable


OVERPASS_ENDPOINTS = [
    "https://overpass-api.de/api/interpreter",
    "https://lz4.overpass-api.de/api/interpreter",
    "https://z.overpass-api.de/api/interpreter",
]

CITY_BBOXES = {
    "beijing": (39.70, 116.00, 40.20, 116.80),
    "guangzhou": (22.85, 112.95, 23.45, 113.65),
    "xiamen": (24.35, 117.90, 24.65, 118.35),
}

CITY_LABELS = {
    "beijing": "\u5317\u4eac\u5e02",
    "guangzhou": "\u5e7f\u5dde\u5e02",
    "xiamen": "\u53a6\u95e8\u5e02",
}

TYPE_IMAGE_MAP = {
    1: [
        "https://images.unsplash.com/photo-1517248135467-4c7edcad34c4?auto=format&fit=crop&w=1200&q=80",
        "https://images.unsplash.com/photo-1552566626-52f8b828add9?auto=format&fit=crop&w=1200&q=80",
        "https://images.unsplash.com/photo-1555396273-367ea4eb4db5?auto=format&fit=crop&w=1200&q=80",
    ],
    2: [
        "https://images.unsplash.com/photo-1514525253161-7a46d19cd819?auto=format&fit=crop&w=1200&q=80",
        "https://images.unsplash.com/photo-1493225457124-a3eb161ffa5f?auto=format&fit=crop&w=1200&q=80",
    ],
    3: [
        "https://images.unsplash.com/photo-1560066984-138dadb4c035?auto=format&fit=crop&w=1200&q=80",
        "https://images.unsplash.com/photo-1521590832167-7bcbfaa6381f?auto=format&fit=crop&w=1200&q=80",
    ],
    4: [
        "https://images.unsplash.com/photo-1517836357463-d25dfeac3438?auto=format&fit=crop&w=1200&q=80",
        "https://images.unsplash.com/photo-1534438327276-14e5300c3a48?auto=format&fit=crop&w=1200&q=80",
    ],
    5: [
        "https://images.unsplash.com/photo-1515377905703-c4788e51af15?auto=format&fit=crop&w=1200&q=80",
        "https://images.unsplash.com/photo-1544161515-4ab6ce6db874?auto=format&fit=crop&w=1200&q=80",
    ],
    6: [
        "https://images.unsplash.com/photo-1519823551278-64ac92734fb1?auto=format&fit=crop&w=1200&q=80",
        "https://images.unsplash.com/photo-1487412720507-e7ab37603c6f?auto=format&fit=crop&w=1200&q=80",
    ],
    7: [
        "https://images.unsplash.com/photo-1516627145497-ae6968895b74?auto=format&fit=crop&w=1200&q=80",
        "https://images.unsplash.com/photo-1503454537195-1dcabb73ffb9?auto=format&fit=crop&w=1200&q=80",
    ],
    8: [
        "https://images.unsplash.com/photo-1514933651103-005eec06c04b?auto=format&fit=crop&w=1200&q=80",
        "https://images.unsplash.com/photo-1470337458703-46ad1756a187?auto=format&fit=crop&w=1200&q=80",
    ],
    9: [
        "https://images.unsplash.com/photo-1529156069898-49953e39b3ac?auto=format&fit=crop&w=1200&q=80",
        "https://images.unsplash.com/photo-1492684223066-81342ee5ff30?auto=format&fit=crop&w=1200&q=80",
    ],
    10: [
        "https://images.unsplash.com/photo-1604654894610-df63bc536371?auto=format&fit=crop&w=1200&q=80",
        "https://images.unsplash.com/photo-1610992015732-2449b76344bc?auto=format&fit=crop&w=1200&q=80",
    ],
}


@dataclass
class ShopRow:
    source_id: str
    city: str
    name: str
    type_id: int
    images: str
    area: str
    address: str
    x: float
    y: float
    avg_price: int
    sold: int
    comments: int
    score: int
    open_hours: str


def log(message: str) -> None:
    print(message, flush=True)


def md5_int(seed: str) -> int:
    return int(hashlib.md5(seed.encode("utf-8")).hexdigest(), 16)


def mysql_cmd(args: list[str]) -> list[str]:
    return [
        args[0],
        "-h",
        args[1],
        "-P",
        str(args[2]),
        "-u",
        args[3],
        f"-p{args[4]}",
        "--default-character-set=utf8mb4",
    ]


def run_mysql_query(mysql_bin: str, host: str, port: int, user: str, password: str, database: str, sql: str) -> str:
    cmd = mysql_cmd([mysql_bin, host, port, user, password]) + ["-N", "-B", "-D", database, "-e", sql]
    result = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip() or "mysql query failed")
    return result.stdout


def exec_mysql_file(mysql_bin: str, host: str, port: int, user: str, password: str, database: str, sql_file: Path) -> None:
    cmd = mysql_cmd([mysql_bin, host, port, user, password]) + ["-D", database]
    with sql_file.open("rb") as handle:
        result = subprocess.run(cmd, stdin=handle, capture_output=True)
    if result.returncode != 0:
        raise RuntimeError(result.stderr.decode("utf-8", errors="replace").strip() or "mysql import failed")


def run_redis(redis_cli: str, host: str, port: int, password: str | None, *args: str) -> subprocess.CompletedProcess[str]:
    cmd = [redis_cli, "-h", host, "-p", str(port)]
    if password:
        cmd.extend(["-a", password])
    cmd.extend(args)
    return subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")


def detect_redis_auth(redis_cli: str, host: str, port: int, password: str | None) -> str | None:
    if password:
        probe = run_redis(redis_cli, host, port, password, "PING")
        if probe.returncode == 0 and probe.stdout.strip() == "PONG":
            return password
    probe = run_redis(redis_cli, host, port, None, "PING")
    if probe.returncode == 0 and probe.stdout.strip() == "PONG":
        return None
    if password:
        return password
    raise RuntimeError(f"Redis auth probe failed: {probe.stderr.strip() or probe.stdout.strip()}")


def escape_sql(value: str) -> str:
    return value.replace("\\", "\\\\").replace("'", "\\'")


def norm_text(value: str | None) -> str:
    if not value:
        return ""
    value = re.sub(r"\s+", " ", value).strip()
    return value


def choose_area(tags: dict[str, str], city_label: str) -> str:
    for key in ("addr:district", "addr:subdistrict", "subdistrict", "district", "addr:neighbourhood", "neighbourhood"):
        if norm_text(tags.get(key)):
            return norm_text(tags[key])[:120]
    return city_label


def choose_address(tags: dict[str, str], city_label: str, area: str, name: str) -> str:
    street = norm_text(tags.get("addr:street"))
    number = norm_text(tags.get("addr:housenumber"))
    full = norm_text(tags.get("addr:full"))
    if full:
        return full[:250]
    if street and number:
        return f"{street}{number}"[:250]
    if street:
        return street[:250]
    house_name = norm_text(tags.get("addr:housename"))
    if house_name:
        return house_name[:250]
    if area and area != city_label:
        return f"{area}{name}"[:250]
    return f"{city_label}{name}"[:250]


def classify_shop(tags: dict[str, str], name: str) -> int | None:
    amenity = norm_text(tags.get("amenity")).lower()
    shop = norm_text(tags.get("shop")).lower()
    leisure = norm_text(tags.get("leisure")).lower()
    beauty = norm_text(tags.get("beauty")).lower()
    lower_name = name.lower()

    if "ktv" in lower_name or amenity == "karaoke_box":
        return 2
    if amenity in {"bar", "pub", "nightclub"}:
        return 8
    if amenity in {"restaurant", "fast_food", "cafe", "food_court", "ice_cream"}:
        return 1
    if "\u8f70\u8db4" in name or "party" in lower_name:
        return 9
    if leisure in {"fitness_centre", "sports_centre"}:
        return 4
    if leisure in {"playground", "amusement_arcade"}:
        return 7
    if shop == "hairdresser":
        return 3
    if shop == "massage":
        return 5
    if amenity == "spa":
        return 6
    if "\u7f8e\u7532" in name or "\u7f8e\u776b" in name or shop == "nail_salon" or beauty == "nails":
        return 10
    if shop in {"beauty", "cosmetics"} or beauty in {"spa", "skin_care", "hair_removal", "eyebrows", "lashes"}:
        return 6
    return None


def build_images(type_id: int, seed: str) -> str:
    images = TYPE_IMAGE_MAP.get(type_id, TYPE_IMAGE_MAP[1])
    rnd = random.Random(md5_int(seed))
    picks = images[:]
    rnd.shuffle(picks)
    return ",".join(picks[: min(3, len(picks))])


def metric_range(seed: str, start: int, end: int) -> int:
    rnd = random.Random(md5_int(seed))
    return rnd.randint(start, end)


def build_shop_row(city_key: str, element: dict) -> ShopRow | None:
    tags = element.get("tags") or {}
    name = norm_text(tags.get("name"))
    if not name or len(name) < 2:
        return None

    type_id = classify_shop(tags, name)
    if not type_id:
        return None

    lat = element.get("lat")
    lon = element.get("lon")
    if lat is None or lon is None:
        center = element.get("center") or {}
        lat = center.get("lat")
        lon = center.get("lon")
    if lat is None or lon is None:
        return None

    city_label = CITY_LABELS[city_key]
    area = choose_area(tags, city_label)
    address = choose_address(tags, city_label, area, name)
    open_hours = norm_text(tags.get("opening_hours"))[:32] or infer_open_hours(type_id, name)
    source_id = f"osm:{element.get('type')}:{element.get('id')}"
    seed = f"{source_id}:{name}:{city_key}"

    if type_id == 1:
        avg_price = metric_range(seed + ":price", 25, 168)
    elif type_id == 8:
        avg_price = metric_range(seed + ":price", 50, 198)
    else:
        avg_price = metric_range(seed + ":price", 39, 299)

    comments = metric_range(seed + ":comments", 18, 3600)
    sold = max(comments + metric_range(seed + ":sold", 10, 2400), comments + 5)
    score = metric_range(seed + ":score", 38, 49)

    return ShopRow(
        source_id=source_id,
        city=city_key,
        name=name[:120],
        type_id=type_id,
        images=build_images(type_id, seed),
        area=area[:120],
        address=address[:250],
        x=round(float(lon), 6),
        y=round(float(lat), 6),
        avg_price=avg_price,
        sold=sold,
        comments=comments,
        score=score,
        open_hours=open_hours,
    )


def infer_open_hours(type_id: int, name: str) -> str:
    if type_id == 2 or "ktv" in name.lower():
        return "12:00-02:00"
    if type_id == 8:
        return "18:00-02:00"
    if type_id == 4:
        return "09:00-22:00"
    if type_id in {5, 6, 10}:
        return "10:00-22:00"
    if type_id == 7:
        return "10:00-21:00"
    return "10:00-22:00"


def tile_bbox(bbox: tuple[float, float, float, float], lat_step: float, lon_step: float) -> Iterable[tuple[float, float, float, float]]:
    min_lat, min_lon, max_lat, max_lon = bbox
    lat = min_lat
    while lat < max_lat:
        next_lat = min(lat + lat_step, max_lat)
        lon = min_lon
        while lon < max_lon:
            next_lon = min(lon + lon_step, max_lon)
            yield (round(lat, 6), round(lon, 6), round(next_lat, 6), round(next_lon, 6))
            lon = next_lon
        lat = next_lat


def build_overpass_query(tile: tuple[float, float, float, float]) -> str:
    lat1, lon1, lat2, lon2 = tile
    return f"""[out:json][timeout:90];
(
  node["amenity"~"restaurant|fast_food|cafe|bar|pub|nightclub|karaoke_box|spa"]({lat1},{lon1},{lat2},{lon2});
  way["amenity"~"restaurant|fast_food|cafe|bar|pub|nightclub|karaoke_box|spa"]({lat1},{lon1},{lat2},{lon2});
  node["shop"~"hairdresser|beauty|cosmetics|massage|nail_salon"]({lat1},{lon1},{lat2},{lon2});
  way["shop"~"hairdresser|beauty|cosmetics|massage|nail_salon"]({lat1},{lon1},{lat2},{lon2});
  node["leisure"~"fitness_centre|sports_centre|playground|amusement_arcade"]({lat1},{lon1},{lat2},{lon2});
  way["leisure"~"fitness_centre|sports_centre|playground|amusement_arcade"]({lat1},{lon1},{lat2},{lon2});
);
out center tags;"""


def fetch_overpass(query: str) -> dict:
    last_error = None
    for endpoint in OVERPASS_ENDPOINTS:
        for attempt in range(3):
            try:
                req = urllib.request.Request(
                    endpoint,
                    data=query.encode("utf-8"),
                    headers={"User-Agent": "zyro-local-osm-import/1.0"},
                )
                with urllib.request.urlopen(req, timeout=180) as resp:
                    return json.load(resp)
            except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as exc:
                last_error = exc
                time.sleep(1.5 * (attempt + 1))
    raise RuntimeError(f"Overpass fetch failed: {last_error}")


def dedup_key(row: ShopRow) -> str:
    return f"{row.source_id}|{row.name.strip().lower()}|{row.address.strip().lower()}|{row.x:.6f}|{row.y:.6f}"


def collect_city_rows(city_key: str, lat_step: float, lon_step: float, sleep_seconds: float) -> list[ShopRow]:
    rows_by_source: dict[str, ShopRow] = {}
    bbox = CITY_BBOXES[city_key]
    tiles = list(tile_bbox(bbox, lat_step, lon_step))
    for index, tile in enumerate(tiles, start=1):
        log(f"[{city_key}] fetching tile {index}/{len(tiles)} {tile}")
        payload = fetch_overpass(build_overpass_query(tile))
        for element in payload.get("elements", []):
            row = build_shop_row(city_key, element)
            if row:
                rows_by_source[row.source_id] = row
        if sleep_seconds > 0:
            time.sleep(sleep_seconds)
    return list(rows_by_source.values())


def build_insert_sql(rows: list[ShopRow]) -> str:
    values = []
    for row in rows:
        values.append(
            "("
            f"'{escape_sql(row.name)}',"
            f"{row.type_id},"
            f"'{escape_sql(row.images)}',"
            f"'{escape_sql(row.area)}',"
            f"'{escape_sql(row.address)}',"
            f"{row.x},"
            f"{row.y},"
            f"{row.avg_price},"
            f"{row.sold},"
            f"{row.comments},"
            f"{row.score},"
            f"'{escape_sql(row.open_hours)}'"
            ")"
        )
    return (
        "INSERT INTO tb_shop "
        "(name, type_id, images, area, address, x, y, avg_price, sold, comments, score, open_hours) VALUES\n"
        + ",\n".join(values)
        + ";\n"
    )


def build_stage_insert_sql(rows: list[ShopRow]) -> str:
    values = []
    for row in rows:
        values.append(
            "("
            f"'{escape_sql(row.source_id)}',"
            f"'{escape_sql(row.city)}',"
            f"'{escape_sql(row.name)}',"
            f"{row.type_id},"
            f"'{escape_sql(row.images)}',"
            f"'{escape_sql(row.area)}',"
            f"'{escape_sql(row.address)}',"
            f"{row.x},"
            f"{row.y},"
            f"{row.avg_price},"
            f"{row.sold},"
            f"{row.comments},"
            f"{row.score},"
            f"'{escape_sql(row.open_hours)}'"
            ")"
        )
    return (
        "INSERT INTO tmp_shop_import_stage "
        "(source_id, city, name, type_id, images, area, address, x, y, avg_price, sold, comments, score, open_hours) VALUES\n"
        + ",\n".join(values)
        + ";\n"
    )


def table_row_count(mysql_bin: str, host: str, port: int, user: str, password: str, database: str, table_name: str) -> int:
    output = run_mysql_query(mysql_bin, host, port, user, password, database, f"SELECT COUNT(*) FROM {table_name}")
    return int(output.strip() or "0")


def import_rows(mysql_bin: str, host: str, port: int, user: str, password: str, database: str, rows: list[ShopRow]) -> int:
    if not rows:
        log("No new rows to insert.")
        return 0
    before_count = table_row_count(mysql_bin, host, port, user, password, database, "tb_shop")
    with tempfile.NamedTemporaryFile("w", suffix=".sql", delete=False, encoding="utf-8") as handle:
        sql_file = Path(handle.name)
        handle.write("SET NAMES utf8mb4;\n")
        handle.write(
            """
CREATE TABLE IF NOT EXISTS tb_shop_import_source (
    source_id VARCHAR(128) NOT NULL PRIMARY KEY,
    shop_id BIGINT UNSIGNED NOT NULL,
    city VARCHAR(32) NOT NULL,
    created_time TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
ALTER TABLE tb_shop_import_source CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
DROP TEMPORARY TABLE IF EXISTS tmp_shop_import_stage;
CREATE TEMPORARY TABLE tmp_shop_import_stage (
    source_id VARCHAR(128) NOT NULL,
    city VARCHAR(32) NOT NULL,
    name VARCHAR(128) NOT NULL,
    type_id BIGINT UNSIGNED NOT NULL,
    images VARCHAR(1024) NOT NULL,
    area VARCHAR(128) NULL,
    address VARCHAR(255) NOT NULL,
    x DOUBLE UNSIGNED NOT NULL,
    y DOUBLE UNSIGNED NOT NULL,
    avg_price BIGINT UNSIGNED NULL,
    sold INT UNSIGNED NOT NULL,
    comments INT UNSIGNED NOT NULL,
    score INT UNSIGNED NOT NULL,
    open_hours VARCHAR(32) NULL,
    PRIMARY KEY (source_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
"""
        )
        chunk_size = 300
        for index in range(0, len(rows), chunk_size):
            handle.write(build_stage_insert_sql(rows[index:index + chunk_size]))
        handle.write(
            """
INSERT INTO tb_shop (name, type_id, images, area, address, x, y, avg_price, sold, comments, score, open_hours)
SELECT s.name, s.type_id, s.images, s.area, s.address, s.x, s.y, s.avg_price, s.sold, s.comments, s.score, s.open_hours
FROM tmp_shop_import_stage s
LEFT JOIN tb_shop_import_source src ON src.source_id = s.source_id
WHERE src.source_id IS NULL;

INSERT IGNORE INTO tb_shop_import_source (source_id, shop_id, city)
SELECT s.source_id, MIN(t.id) AS shop_id, s.city
FROM tmp_shop_import_stage s
JOIN tb_shop t
  ON t.name = s.name
 AND t.address = s.address
 AND ROUND(t.x, 6) = ROUND(s.x, 6)
 AND ROUND(t.y, 6) = ROUND(s.y, 6)
GROUP BY s.source_id, s.city;
"""
        )
    try:
        exec_mysql_file(mysql_bin, host, port, user, password, database, sql_file)
    finally:
        sql_file.unlink(missing_ok=True)
    after_count = table_row_count(mysql_bin, host, port, user, password, database, "tb_shop")
    return max(after_count - before_count, 0)


def export_rows(output_path: Path, rows: list[ShopRow]) -> None:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    payload = [
        {
            "source_id": row.source_id,
            "city": row.city,
            "name": row.name,
            "type_id": row.type_id,
            "images": row.images,
            "area": row.area,
            "address": row.address,
            "x": row.x,
            "y": row.y,
            "avg_price": row.avg_price,
            "sold": row.sold,
            "comments": row.comments,
            "score": row.score,
            "open_hours": row.open_hours,
        }
        for row in rows
    ]
    output_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


def rebuild_redis_geo(
    mysql_bin: str,
    mysql_host: str,
    mysql_port: int,
    mysql_user: str,
    mysql_password: str,
    mysql_db: str,
    redis_cli: str,
    redis_host: str,
    redis_port: int,
    redis_password: str | None,
) -> None:
    redis_password = detect_redis_auth(redis_cli, redis_host, redis_port, redis_password)
    scan = run_redis(redis_cli, redis_host, redis_port, redis_password, "--scan", "--pattern", "shop:geo:*")
    if scan.returncode != 0:
        raise RuntimeError(scan.stderr.strip() or "redis scan failed")
    keys = [line.strip() for line in scan.stdout.splitlines() if line.strip()]
    if keys:
        delete = run_redis(redis_cli, redis_host, redis_port, redis_password, "DEL", *keys)
        if delete.returncode != 0:
            raise RuntimeError(delete.stderr.strip() or "redis key delete failed")

    sql = "SELECT id, type_id, x, y FROM tb_shop WHERE x IS NOT NULL AND y IS NOT NULL ORDER BY type_id, id"
    output = run_mysql_query(mysql_bin, mysql_host, mysql_port, mysql_user, mysql_password, mysql_db, sql)
    bucket: dict[int, list[tuple[str, str, str]]] = {}
    for line in output.splitlines():
        if not line.strip():
            continue
        shop_id, type_id, x, y = line.split("\t")
        bucket.setdefault(int(type_id), []).append((x, y, shop_id))

    for type_id, members in bucket.items():
        key = f"shop:geo:{type_id}"
        for index in range(0, len(members), 200):
            args = ["GEOADD", key]
            for x, y, shop_id in members[index:index + 200]:
                args.extend([x, y, shop_id])
            cmd = run_redis(redis_cli, redis_host, redis_port, redis_password, *args)
            if cmd.returncode != 0:
                raise RuntimeError(cmd.stderr.strip() or f"redis GEOADD failed for {key}")


def main() -> int:
    parser = argparse.ArgumentParser(description="Import OSM shops into zyro-local tb_shop")
    parser.add_argument("--cities", default="beijing,guangzhou,xiamen", help="comma-separated city keys")
    parser.add_argument("--mysql-bin", default=os.environ.get("MYSQL_BIN", "mysql"))
    parser.add_argument("--mysql-host", default=os.environ.get("MYSQL_HOST", "127.0.0.1"))
    parser.add_argument("--mysql-port", type=int, default=int(os.environ.get("MYSQL_PORT", "3306")))
    parser.add_argument("--mysql-user", default=os.environ.get("MYSQL_USERNAME", "root"))
    parser.add_argument("--mysql-password", default=os.environ.get("MYSQL_PASSWORD", "1234"))
    parser.add_argument("--mysql-db", default=os.environ.get("MYSQL_DB", "hmdp"))
    parser.add_argument("--redis-cli", default=os.environ.get("REDIS_CLI", r"E:\Develop\Redis-x64-5.0.14.1\redis-cli.exe"))
    parser.add_argument("--redis-host", default=os.environ.get("REDIS_HOST", "127.0.0.1"))
    parser.add_argument("--redis-port", type=int, default=int(os.environ.get("REDIS_PORT", "6379")))
    parser.add_argument("--redis-password", default=os.environ.get("REDIS_PASSWORD"))
    parser.add_argument("--lat-step", type=float, default=0.12)
    parser.add_argument("--lon-step", type=float, default=0.18)
    parser.add_argument("--sleep-seconds", type=float, default=0.2)
    parser.add_argument("--export-json", default="data/import/osm-shops.json")
    parser.add_argument("--sync-redis-geo", action="store_true")
    args = parser.parse_args()

    selected_cities = [city.strip().lower() for city in args.cities.split(",") if city.strip()]
    invalid = [city for city in selected_cities if city not in CITY_BBOXES]
    if invalid:
        raise SystemExit(f"Unsupported city keys: {', '.join(invalid)}")

    rows: list[ShopRow] = []
    seen: set[str] = set()

    for city in selected_cities:
        city_rows = collect_city_rows(city, args.lat_step, args.lon_step, args.sleep_seconds)
        kept = 0
        for row in city_rows:
            key = dedup_key(row)
            if key in seen:
                continue
            seen.add(key)
            rows.append(row)
            kept += 1
        log(f"[{city}] prepared {kept} new rows")

    export_path = Path(args.export_json)
    export_rows(export_path, rows)
    log(f"Exported {len(rows)} rows to {export_path}")
    inserted = import_rows(
        args.mysql_bin, args.mysql_host, args.mysql_port, args.mysql_user, args.mysql_password, args.mysql_db, rows
    )
    log(f"Inserted {inserted} new rows into {args.mysql_db}.tb_shop")

    if args.sync_redis_geo:
        rebuild_redis_geo(
            args.mysql_bin,
            args.mysql_host,
            args.mysql_port,
            args.mysql_user,
            args.mysql_password,
            args.mysql_db,
            args.redis_cli,
            args.redis_host,
            args.redis_port,
            args.redis_password,
        )
        log("Redis GEO indexes rebuilt.")

    return 0


if __name__ == "__main__":
    sys.exit(main())
