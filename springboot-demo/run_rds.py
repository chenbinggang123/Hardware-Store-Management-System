from __future__ import annotations

import argparse
import os
import subprocess
import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
LIB_DIR = (SCRIPT_DIR / "scripts" / "lib").resolve()
if str(LIB_DIR) not in sys.path:
    sys.path.insert(0, str(LIB_DIR))

from common import get_config_value, load_config, resolve_python, select_value  # noqa: E402


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run Spring Boot app against RDS config.")
    parser.add_argument("--config-path", default="scripts/rds/rds.config.example.json")
    parser.add_argument("--db-host")
    parser.add_argument("--db-port", type=int)
    parser.add_argument("--db-name")
    parser.add_argument("--db-username")
    parser.add_argument("--db-password")
    parser.add_argument("--server-port", type=int)
    parser.add_argument("--compile-only", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    config = load_config(args.config_path)
    db_host = select_value(args.db_host, get_config_value(config, "connection.host"))
    db_port = int(select_value(args.db_port, get_config_value(config, "connection.port"), 3306))
    db_name = select_value(args.db_name, get_config_value(config, "connection.database"), "hardware_store")
    db_username = select_value(args.db_username, get_config_value(config, "connection.username"))
    db_password = select_value(args.db_password, get_config_value(config, "connection.password"), "")
    server_port = int(select_value(args.server_port, get_config_value(config, "application.serverPort"), 8084))

    if not db_host:
        raise SystemExit("ERROR: db host is required.")
    if not db_username:
        raise SystemExit("ERROR: db username is required.")

    env = os.environ.copy()
    env["APP_PROFILE"] = "mysql"
    env["DB_HOST"] = str(db_host)
    env["DB_PORT"] = str(db_port)
    env["DB_NAME"] = str(db_name)
    env["DB_USERNAME"] = str(db_username)
    env["DB_PASSWORD"] = str(db_password)

    print("Running application against RDS: {0}:{1}/{2}".format(db_host, db_port, db_name))

    command = ["powershell", "-ExecutionPolicy", "Bypass", "-File", str(SCRIPT_DIR / "run-dev.ps1"), "-Port", str(server_port)]
    if args.compile_only:
        command.insert(-2, "-CompileOnly")

    completed = subprocess.run(command, cwd=str(SCRIPT_DIR), env=env)
    return completed.returncode


if __name__ == "__main__":
    raise SystemExit(main())
