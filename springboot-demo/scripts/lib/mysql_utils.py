from __future__ import annotations

import decimal
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Sequence, Tuple

import pymysql


def connect_mysql(host: str, port: int, user: str, password: str, database: Optional[str] = None, autocommit: bool = True):
    return pymysql.connect(
        host=host,
        port=int(port),
        user=user,
        password=password,
        database=database,
        charset="utf8mb4",
        autocommit=autocommit,
        cursorclass=pymysql.cursors.Cursor,
    )


def split_sql_statements(sql_text: str) -> List[str]:
    statements: List[str] = []
    buffer: List[str] = []
    in_single = False
    in_double = False
    in_line_comment = False
    in_block_comment = False
    escape = False
    index = 0
    length = len(sql_text)

    while index < length:
        char = sql_text[index]
        nxt = sql_text[index + 1] if index + 1 < length else ""

        if in_line_comment:
            buffer.append(char)
            if char == "\n":
                in_line_comment = False
            index += 1
            continue

        if in_block_comment:
            buffer.append(char)
            if char == "*" and nxt == "/":
                buffer.append(nxt)
                in_block_comment = False
                index += 2
                continue
            index += 1
            continue

        if not in_single and not in_double:
            if char == "-" and nxt == "-":
                in_line_comment = True
                buffer.append(char)
                buffer.append(nxt)
                index += 2
                continue
            if char == "/" and nxt == "*":
                in_block_comment = True
                buffer.append(char)
                buffer.append(nxt)
                index += 2
                continue

        if char == "\\" and (in_single or in_double):
            buffer.append(char)
            escape = not escape
            index += 1
            continue

        if char == "'" and not in_double and not escape:
            in_single = not in_single
        elif char == '"' and not in_single and not escape:
            in_double = not in_double

        if char == ";" and not in_single and not in_double:
            statement = "".join(buffer).strip()
            if statement:
                statements.append(statement)
            buffer = []
            escape = False
            index += 1
            continue

        buffer.append(char)
        escape = False
        index += 1

    tail = "".join(buffer).strip()
    if tail:
        statements.append(tail)
    return statements


def execute_sql_script(connection, sql_text: str) -> int:
    statements = split_sql_statements(sql_text)
    executed = 0
    with connection.cursor() as cursor:
        for statement in statements:
            stripped = statement.strip()
            if not stripped:
                continue
            cursor.execute(stripped)
            executed += 1
    connection.commit()
    return executed


def ensure_database(host: str, port: int, user: str, password: str, database: str) -> None:
    connection = connect_mysql(host, port, user, password, database=None, autocommit=True)
    try:
        with connection.cursor() as cursor:
            cursor.execute(
                "CREATE DATABASE IF NOT EXISTS `{0}` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci".format(database)
            )
    finally:
        connection.close()


def escape_value(connection, value: Any) -> str:
    if value is None:
        return "NULL"
    if isinstance(value, bytes):
        return "0x{0}".format(value.hex())
    if isinstance(value, (int, float)):
        return str(value)
    if isinstance(value, decimal.Decimal):
        return format(value, "f")
    return connection.escape(value)


def dump_database(host: str, port: int, user: str, password: str, database: str, output_path: Path) -> Tuple[int, int]:
    connection = connect_mysql(host, port, user, password, database=database, autocommit=True)
    table_count = 0
    row_count = 0

    try:
        with connection.cursor() as cursor, output_path.open("w", encoding="utf-8", newline="\n") as handle:
            cursor.execute("SHOW TABLES")
            tables = [row[0] for row in cursor.fetchall()]
            table_count = len(tables)

            handle.write("SET NAMES utf8mb4;\n")
            handle.write("SET FOREIGN_KEY_CHECKS = 0;\n\n")

            for table_name in tables:
                cursor.execute("SHOW CREATE TABLE `{0}`".format(table_name))
                _, create_sql = cursor.fetchone()
                handle.write(create_sql.rstrip() + ";\n\n")

                cursor.execute("SELECT * FROM `{0}`".format(table_name))
                rows = cursor.fetchall()
                row_count += len(rows)
                if not rows:
                    continue

                column_names = [column[0] for column in cursor.description]
                column_sql = ", ".join("`{0}`".format(column_name) for column_name in column_names)
                value_lines = []
                for row in rows:
                    values_sql = ", ".join(escape_value(connection, value) for value in row)
                    value_lines.append("({0})".format(values_sql))
                handle.write(
                    "INSERT INTO `{0}` ({1}) VALUES\n{2};\n\n".format(
                        table_name,
                        column_sql,
                        ",\n".join(value_lines),
                    )
                )

            handle.write("SET FOREIGN_KEY_CHECKS = 1;\n")
    finally:
        connection.close()

    return table_count, row_count


def query_scalar(connection, query: str) -> Any:
    with connection.cursor() as cursor:
        cursor.execute(query)
        row = cursor.fetchone()
        return row[0] if row else None


def query_row(connection, query: str) -> Optional[Sequence[Any]]:
    with connection.cursor() as cursor:
        cursor.execute(query)
        return cursor.fetchone()


def table_counts(connection, table_names: Iterable[str]) -> Dict[str, int]:
    result: Dict[str, int] = {}
    with connection.cursor() as cursor:
        for table_name in table_names:
            cursor.execute("SELECT COUNT(*) FROM `{0}`".format(table_name))
            result[table_name] = int(cursor.fetchone()[0])
    return result
