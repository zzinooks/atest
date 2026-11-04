"""HAR → API 명세 초안 (REA 방법론 1~2단계 연습용).

민감정보 처리: Authorization 값, 토큰, 비밀번호, 혈당값은 출력하지 않고 타입만 남긴다.
사용: python3 -I har_analyze.py capture.har > analysis.md
"""
import json
import re
import sys
from collections import OrderedDict
from urllib.parse import urlsplit, parse_qsl

SENSITIVE_KEYS = re.compile(r"(token|password|authorization|secret)(?!type)", re.I)


def schema(v, depth=0):
    if isinstance(v, dict):
        return {k: ("<redacted>" if SENSITIVE_KEYS.search(k) else schema(x, depth + 1)) for k, x in v.items()}
    if isinstance(v, list):
        return [schema(v[0], depth + 1)] if v else []
    if v is None:
        return "null"
    if isinstance(v, bool):
        return "boolean"
    if isinstance(v, (int, float)):
        return "number"
    if isinstance(v, str):
        if re.fullmatch(r"\d{4}-\d{2}-\d{2}T[\d:.]+(Z|[+-]\d{2}:\d{2})", v):
            return "datetime(" + ("UTC" if v.endswith("Z") else "offset") + ")"
        return "string"
    return type(v).__name__


def merge(a, b):
    """같은 엔드포인트의 여러 응답 스키마 합치기 (null 과 타입이 섞이면 'type|null')."""
    if isinstance(a, dict) and isinstance(b, dict):
        return {k: merge(a.get(k, "absent"), b.get(k, "absent")) for k in OrderedDict.fromkeys([*a, *b])}
    if isinstance(a, list) and isinstance(b, list):
        return [merge(a[0], b[0])] if a and b else (a or b)
    if a == b:
        return a
    return "|".join(sorted({str(a), str(b)}))


def main(path):
    har = json.load(open(path, encoding="utf-8"))
    eps = OrderedDict()
    for e in har["log"]["entries"]:
        req, res = e["request"], e["response"]
        u = urlsplit(req["url"])
        if u.path.startswith("/emulator/"):
            continue
        key = f'{req["method"]} {u.path}'
        ep = eps.setdefault(key, {"status": set(), "params": set(), "auth": set(), "req": None, "res": {}, "headers": set()})
        ep["status"].add(res["status"])
        ep["params"].update(k for k, _ in parse_qsl(u.query))
        hdrs = {h["name"].lower(): h["value"] for h in req["headers"]}
        ep["auth"].add(hdrs["authorization"].split(" ")[0] if "authorization" in hdrs else "none")
        ep["headers"].update(h["name"].lower() for h in res["headers"] if h["name"].lower() in ("retry-after", "content-type"))
        if req.get("postData", {}).get("text"):
            ep["req"] = schema(json.loads(req["postData"]["text"]))
        text = res.get("content", {}).get("text")
        if text:
            body = json.loads(text)
            s = schema(body)
            ep["res"][res["status"]] = merge(ep["res"][res["status"]], s) if res["status"] in ep["res"] else s

    print(f"# HAR 분석 결과 — {len(har['log']['entries'])}개 요청, {len(eps)}개 엔드포인트\n")
    print("| 엔드포인트 | 인증 | 쿼리 파라미터 | 관찰된 상태코드 |")
    print("|---|---|---|---|")
    for k, ep in eps.items():
        print(f"| `{k}` | {', '.join(sorted(ep['auth']))} | {', '.join(sorted(ep['params'])) or '-'} | {', '.join(map(str, sorted(ep['status'])))} |")
    for k, ep in eps.items():
        print(f"\n## `{k}`\n")
        if ep["req"]:
            print("요청 본문:\n```json\n" + json.dumps(ep["req"], ensure_ascii=False, indent=2) + "\n```")
        for st, s in sorted(ep["res"].items()):
            print(f"응답 {st}:\n```json\n" + json.dumps(s, ensure_ascii=False, indent=2) + "\n```")


if __name__ == "__main__":
    main(sys.argv[1])
