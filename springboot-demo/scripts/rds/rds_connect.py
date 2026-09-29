from __future__ import annotations

import argparse
import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
LIB_DIR = (SCRIPT_DIR / ".." / "lib").resolve()
if str(LIB_DIR) not in sys.path:
    sys.path.insert(0, str(LIB_DIR))

from common import get_config_value, load_config, select_value  # noqa: E402
from mysql_utils import connect_mysql, query_row  # noqa: E402


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Test RDS MySQL connection using PyMySQL.")
    parser.add_argument("config_path", nargs="?", default="scripts/rds/rds.config.example.json")
    parser.add_argument("--host")
    parser.add_argument("--port", type=int)
    parser.add_argument("--database")
    parser.add_argument("--username")
    parser.add_argument("--password")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    config = load_config(args.config_path)
    host = select_value(args.host, get_config_value(config, "connection.host"))
    port = int(select_value(args.port, get_config_value(config, "connection.port"), 3306))
    database = select_value(args.database, get_config_value(config, "connection.database"), "")
    username = select_value(args.username, get_config_value(config, "connection.username"))
    password = select_value(args.password, get_config_value(config, "connection.password"), "")

    if not host:
        raise SystemExit("ERROR: host is required.")
    if not username:
        raise SystemExit("ERROR: username is required.")

    print("Host: {0}".format(host))
    print("Port: {0}".format(port))
    print("Database: {0}".format(database))
    print("Username: {0}".format(username))
    print()

    connection = connect_mysql(host, port, username, password, database or None)
    try:
        row = query_row(connection, "SELECT VERSION() AS version, DATABASE() AS current_db, NOW() AS server_time")
        print("SUCCESS: Connected to RDS MySQL!")
        print("  MySQL Version: {0}".format(row[0]))
        print("  Current DB:    {0}".format(row[1]))
        print("  Server Time:   {0}".format(row[2]))
    finally:
        connection.close()

    print()
    print("Connection test passed.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print("CONNECTION FAILED: {0}".format(exc))
        raise SystemExit(1)
