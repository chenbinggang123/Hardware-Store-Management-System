from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import xml.etree.ElementTree as et
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, Iterable, Optional


def script_path(*parts: str) -> Path:
    return Path(__file__).resolve().parent.joinpath(*parts)


def project_root() -> Path:
    return script_path("..", "..").resolve()


def is_text_value(value: Any) -> bool:
    return value is not None and str(value).strip() != ""


def resolve_path(path_value: Optional[str], base_path: Optional[Path] = None) -> Optional[Path]:
    if not is_text_value(path_value):
        return None
    candidate = Path(str(path_value))
    if candidate.is_absolute():
        return candidate
    root = base_path or project_root()
    return (root / candidate).resolve()


def ensure_directory(path: Path) -> Path:
    path.mkdir(parents=True, exist_ok=True)
    return path.resolve()


def timestamp() -> str:
    return datetime.now().strftime("%Y%m%d_%H%M%S")


def datestamp() -> str:
    return datetime.now().strftime("%Y%m%d")


def write_log(message: str, level: str = "INFO", log_file: Optional[Path] = None) -> None:
    line = "[{0}] [{1}] {2}".format(datetime.now().strftime("%Y-%m-%d %H:%M:%S"), level.upper(), message)
    print(line)
    if log_file is not None:
        ensure_directory(log_file.parent)
        with log_file.open("a", encoding="utf-8") as handle:
            handle.write(line + "\n")


def read_json(path: Path) -> Any:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def write_json(path: Path, data: Any) -> Path:
    ensure_directory(path.parent)
    with path.open("w", encoding="utf-8") as handle:
        json.dump(data, handle, ensure_ascii=False, indent=2)
    return path.resolve()


def load_config(config_path: Optional[str]) -> Optional[Dict[str, Any]]:
    resolved = resolve_path(config_path)
    if resolved is None:
        return None
    if not resolved.exists():
        raise FileNotFoundError("Config file not found: {0}".format(resolved))
    return read_json(resolved)


def get_config_value(config: Any, path: str, default: Any = None) -> Any:
    if config is None:
        return default
    current = config
    for segment in path.split("."):
        if current is None:
            return default
        if isinstance(current, dict):
            if segment not in current:
                return default
            current = current[segment]
            continue
        if not hasattr(current, segment):
            return default
        current = getattr(current, segment)
    return default if current is None else current


def select_value(explicit_value: Any, config_value: Any, default_value: Any = None) -> Any:
    if explicit_value is not None:
        if isinstance(explicit_value, str):
            if is_text_value(explicit_value):
                return explicit_value
        else:
            return explicit_value
    if config_value is not None:
        if isinstance(config_value, str):
            if is_text_value(config_value):
                return config_value
        else:
            return config_value
    return default_value


def resolve_executable(preferred_path: Optional[str], command_names: Iterable[str], candidate_paths: Iterable[str]) -> Optional[str]:
    resolved_preferred = resolve_path(preferred_path)
    if resolved_preferred is not None and resolved_preferred.exists():
        return str(resolved_preferred)

    for command_name in command_names:
        found = shutil.which(command_name)
        if found:
            return found

    for candidate in candidate_paths:
        candidate_path = Path(candidate)
        if candidate_path.exists():
            return str(candidate_path)
    return None


def resolve_mysql_cli(preferred_path: Optional[str] = None) -> Optional[str]:
    return resolve_executable(
        preferred_path,
        command_names=("mysql",),
        candidate_paths=(
            r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe",
            r"C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe",
            r"C:\Program Files\MySQL\MySQL Server 9.0\bin\mysql.exe",
        ),
    )


def resolve_mysqldump(preferred_path: Optional[str] = None) -> Optional[str]:
    return resolve_executable(
        preferred_path,
        command_names=("mysqldump",),
        candidate_paths=(
            r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqldump.exe",
            r"C:\Program Files\MySQL\MySQL Server 8.4\bin\mysqldump.exe",
            r"C:\Program Files\MySQL\MySQL Server 9.0\bin\mysqldump.exe",
        ),
    )


def app_version() -> str:
    pom_path = project_root() / "pom.xml"
    if not pom_path.exists():
        return "unknown"
    try:
        root = et.fromstring(pom_path.read_text(encoding="utf-8"))
        version_node = root.find("{*}version")
        if version_node is not None and is_text_value(version_node.text):
            return str(version_node.text).strip()
    except Exception:
        return "unknown"
    return "unknown"


def tool_version(command: str, args: Optional[Iterable[str]] = None) -> str:
    command_args = [command] + list(args or ["--version"])
    try:
        completed = subprocess.run(command_args, capture_output=True, text=True, check=False)
        output = (completed.stdout or completed.stderr or "").strip()
        if completed.returncode == 0 and output:
            return output.splitlines()[0]
    except Exception:
        pass
    return "unknown"


def relative_path(base_path: Path, target_path: Path) -> str:
    try:
        return str(target_path.resolve().relative_to(base_path.resolve()))
    except ValueError:
        return str(target_path.resolve())


def backup_directory_map(output_root: Optional[str]) -> Dict[str, Path]:
    root = ensure_directory(resolve_path(select_value(output_root, None, "target/backups")) or (project_root() / "target/backups"))
    return {
        "root": root,
        "full": ensure_directory(root / "full"),
        "incremental": ensure_directory(root / "incremental"),
        "restore": ensure_directory(root / "restore"),
        "logs": ensure_directory(root / "logs"),
        "manifest": ensure_directory(root / "manifest"),
    }


def manifest_base(backup_type: str, db_name: str, source_host: str, mysql_version: str) -> Dict[str, Any]:
    return {
        "backupType": backup_type,
        "generatedAt": datetime.now().strftime("%Y-%m-%dT%H:%M:%S"),
        "dbName": db_name,
        "sourceHost": source_host,
        "appVersion": app_version(),
        "mysqlVersion": mysql_version,
        "status": "PENDING",
        "uploadStatus": "SKIPPED",
    }


def run_subprocess(args: Iterable[str], cwd: Optional[Path] = None, env: Optional[Dict[str, str]] = None, stdin_text: Optional[str] = None) -> subprocess.CompletedProcess:
    return subprocess.run(
        list(args),
        cwd=str(cwd) if cwd else None,
        env=env,
        input=stdin_text,
        text=True,
        capture_output=True,
        check=False,
    )


def resolve_python() -> str:
    return sys.executable or "python"
