#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""minirag MCP server 冒烟测试（SSE 传输）：initialize → tools/list → tools/call query。

用法：python eval/mcp_smoke.py [base_url]   # 默认 http://localhost:18090
"""
import base64
import http.client
import json
import os
import sys
import threading
import time
import urllib.parse

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:18090"
AUTH = os.environ.get("MCP_AUTH")  # 可选，"user:pass" 用于反代 basicauth
u = urllib.parse.urlparse(BASE)
HOST, PORT = u.hostname, u.port or (443 if u.scheme == "https" else 80)
AUTH_HEADER = {"Authorization": "Basic " + base64.b64encode(AUTH.encode()).decode()} if AUTH else {}
BASE_PATH = u.path.rstrip("/")

events = []


def mkconn(timeout):
    cls = http.client.HTTPSConnection if u.scheme == "https" else http.client.HTTPConnection
    return cls(HOST, PORT, timeout=timeout)


def main():
    sse = mkconn(None)
    sse.request("GET", BASE_PATH + "/sse", headers={"Accept": "text/event-stream", **AUTH_HEADER})
    resp = sse.getresponse()
    assert resp.status == 200, f"/sse 状态 {resp.status}"

    endpoint = None

    def pump():
        nonlocal endpoint
        while True:
            raw = resp.fp.readline()
            if not raw:
                break
            line = raw.decode("utf-8").strip()
            if line.startswith("data:"):
                payload = line[5:].strip()
                events.append(payload)
                if payload.startswith("/"):  # endpoint 事件携带消息端点
                    endpoint = payload

    t = threading.Thread(target=pump, daemon=True)
    t.start()
    for _ in range(40):
        if endpoint:
            break
        time.sleep(0.25)
    assert endpoint, "未收到 endpoint 事件"
    print(f"消息端点: {endpoint}")

    msg = http.client.HTTPConnection(HOST, PORT, timeout=120) if u.scheme != "https" else http.client.HTTPSConnection(HOST, PORT, timeout=120)

    def post(payload):
        msg.request("POST", endpoint, body=json.dumps(payload),
                    headers={"Content-Type": "application/json", **AUTH_HEADER})
        r = msg.getresponse()
        r.read()
        assert r.status in (200, 202), f"POST {r.status}"

    def notify(payload):
        post(payload)  # 通知无 id，不会有响应事件

    def rpc(payload):
        post(payload)
        for _ in range(300):
            for item in list(events):
                if not item.startswith("{"):
                    continue
                data = json.loads(item)
                if data.get("id") == payload["id"]:
                    return data
            time.sleep(0.1)
        raise TimeoutError(f"等待响应超时: {payload['method']}")

    init = rpc({"jsonrpc": "2.0", "id": 1, "method": "initialize",
                "params": {"protocolVersion": "2024-11-05", "capabilities": {},
                           "clientInfo": {"name": "smoke", "version": "0"}}})
    info = init["result"]["serverInfo"]
    print(f"initialize OK: {info['name']} v{info['version']}")
    notify({"jsonrpc": "2.0", "method": "notifications/initialized"})

    tools = rpc({"jsonrpc": "2.0", "id": 2, "method": "tools/list"})
    names = [t["name"] for t in tools["result"]["tools"]]
    print(f"tools/list: {names}")
    assert "query" in names and "ingest_text" in names, "工具未注册"

    q = rpc({"jsonrpc": "2.0", "id": 3, "method": "tools/call",
             "params": {"name": "query", "arguments": {"question": "年假有几天"}}})
    text = q["result"]["content"][0]["text"]
    print(f"tools/call query -> {text[:200]}")
    assert not q["result"].get("isError"), "工具调用返回错误"
    print("\nMCP 冒烟全部通过")


if __name__ == "__main__":
    main()
