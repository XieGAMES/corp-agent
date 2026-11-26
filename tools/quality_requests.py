"""Заявки в службу качества: список, ответ с сохранением в базу знаний, удаление.

Нужна учётная запись службы качества (роль QUALITY): учётные данные берутся из
RAG_USERNAME и RAG_PASSWORD (см. rag_api.py).

Использование:
    python quality_requests.py list [all]         открытые заявки (all — вместе с отвеченными)
    python quality_requests.py answer <номер> <файл UTF-8 с ответом>
    python quality_requests.py answer <номер> -   текст ответа читается со стандартного ввода
    python quality_requests.py delete <номер>

Ответ передаётся файлом в UTF-8, а не аргументом командной строки: консоль Windows
перекодирует кириллицу в аргументах.
"""
import json
import sys
import urllib.error
import urllib.request

import rag_api

BASE = rag_api.BASE + "/api/quality-requests"


def call(method, path, body=None):
    """→ (статус HTTP, разобранный JSON или текст ответа). Ошибки HTTP не бросает."""
    headers = {"Content-Type": "application/json; charset=utf-8"}
    headers.update(rag_api.auth_headers())
    req = urllib.request.Request(
        BASE + path,
        data=None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8"),
        headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=600) as r:
            raw = r.read()
            return r.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", errors="replace")


def show_list(items):
    if not items:
        print("Заявок нет")
        return
    for r in items:
        print(f"№{r['requestId']} · {r['createdAt']} · "
              f"{'отвечена' if r['status'] == 'answered' else 'открыта'} · база знаний {r['kbId']}")
        print(f"   вопрос: {r['question']}")
        print(f"   причина: {r['reasonTitle']}")
        if r["status"] == "answered":
            print(f"   ответ ({r.get('answeredBy')}, {r.get('answeredAt')}): {r['answer']}")
            print(f"   документ в базе знаний: {r['answerFile']}")


def fail(status, data):
    if status == 403:
        print("Ошибка: нужна учётная запись службы качества "
              "(роль QUALITY) — задайте RAG_USERNAME и RAG_PASSWORD", file=sys.stderr)
    elif status == 401:
        print("Ошибка: неверные учётные данные (RAG_USERNAME / RAG_PASSWORD)", file=sys.stderr)
    else:
        print(f"Ошибка HTTP {status}: {data}", file=sys.stderr)
    sys.exit(1)


def main():
    args = sys.argv[1:]
    cmd, rest = (args[0], args[1:]) if args else ("", [])
    if cmd == "list":
        status, data = call("GET", "?openOnly=" + ("false" if "all" in rest else "true"))
        if status != 200 or not isinstance(data, list):
            fail(status, data)
        show_list(data)
    elif cmd == "answer" and len(rest) == 2:
        text = sys.stdin.read() if rest[1] == "-" else open(rest[1], encoding="utf-8").read()
        status, data = call("POST", f"/{rest[0]}/answer", {"answer": text})
        if status != 200 or not isinstance(data, dict):
            fail(status, data)
        print(f"Заявка №{data['requestId']}: ответ сохранён в базе знаний «{data['kbId']}» "
              f"документом {data['fileName']} (чанков: {data['chunkCount']}) — "
              f"теперь агент ответит на этот вопрос сам")
    elif cmd == "delete" and len(rest) == 1:
        status, data = call("DELETE", f"/{rest[0]}")
        if status != 204:
            fail(status, data)
        print(f"Заявка №{rest[0]} удалена (документ в базе знаний, если он был, остался)")
    else:
        print(__doc__)
        sys.exit(1)


if __name__ == "__main__":
    main()
