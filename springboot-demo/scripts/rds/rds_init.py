from __future__ import annotations

import argparse
import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
LIB_DIR = (SCRIPT_DIR / ".." / "lib").resolve()
if str(LIB_DIR) not in sys.path:
    sys.path.insert(0, str(LIB_DIR))

from common import get_config_value, load_config, project_root, select_value  # noqa: E402
from mysql_utils import connect_mysql, ensure_database, execute_sql_script  # noqa: E402


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Initialize RDS MySQL database using schema.sql and data.sql.")
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
    database = select_value(args.database, get_config_value(config, "connection.database"), "hardware_store")
    username = select_value(args.username, get_config_value(config, "connection.username"))
    password = select_value(args.password, get_config_value(config, "connection.password"), "")

    if not host:
        raise SystemExit("ERROR: host is required.")
    if not username:
        raise SystemExit("ERROR: username is required.")

    resource_root = project_root() / "src" / "main" / "resources"
    schema_path = resource_root / "schema.sql"
    data_path = resource_root / "data.sql"

    if not schema_path.exists():
        raise FileNotFoundError("Schema file not found: {0}".format(schema_path))
    if not data_path.exists():
        raise FileNotFoundError("Seed data file not found: {0}".format(data_path))

    print("Initializing database: {0}:{1}/{2}".format(host, port, database))
    ensure_database(host, port, username, password, database)

    connection = connect_mysql(host, port, username, password, database)
    try:
        schema_count = execute_sql_script(connection, schema_path.read_text(encoding="utf-8"))
        data_count = execute_sql_script(connection, data_path.read_text(encoding="utf-8"))
    finally:
        connection.close()

    print("Database initialization completed.")
    print("Database: {0}".format(database))
    print("Schema: {0}".format(schema_path))
    print("Seed data: {0}".format(data_path))
    print("Executed schema statements: {0}".format(schema_count))
    print("Executed data statements: {0}".format(data_count))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print("INITIALIZATION FAILED: {0}".format(exc))
        raise SystemExit(1)
