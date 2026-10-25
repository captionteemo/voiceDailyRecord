#!/usr/bin/env python3
"""GitHub OAuth 授权码流程（本地回环回调）。

本机代理对 github.com 的连接呈间歇性丢包（同一 URL 有时 200、有时超时），
GCM 自带的浏览器流程在此环境下会随机 502，且 GUI 弹窗在无人值守时会被取消。
本脚本手工完成流程并**自带重试**，把不稳定因素收敛掉：

1. 启动本地回环 HTTP 服务器（固定端口，便于构造 redirect_uri）
2. 打印授权链接 → 用户在浏览器打开并确认
3. GitHub 回调本机，拿到 code
4. 用 code 换 access_token（带重试）
5. 打印 token（由调用方写入 git 凭据存储）

用法：python3 tools/github_oauth_local.py [--port 8765]
"""
import http.server
import json
import os
import sys
import threading
import time
import urllib.parse
import urllib.request

CLIENT_ID = os.environ.get("GH_CLIENT_ID", "194ba290ce533465310d")
CLIENT_SECRET = os.environ.get("GH_CLIENT_SECRET", "")
SCOPE = "repo workflow gist"
DEFAULT_PORT = 8765


def http_get_with_retry(url: str, tries: int = 8, timeout: int = 20) -> dict:
    """GET JSON，带指数退避重试（对抗代理间歇性丢包）。"""
    last = ""
    for i in range(tries):
        try:
            req = urllib.request.Request(
                url,
                headers={"Accept": "application/json", "User-Agent": "wb-oauth"},
            )
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                return json.loads(resp.read().decode())
        except Exception as exc:  # noqa: BLE001
            last = f"{type(exc).__name__}: {exc}"
            wait = min(2 ** i, 15)
            print(f"  [重试 {i + 1}/{tries}] {last} → {wait}s 后再试")
            time.sleep(wait)
    raise RuntimeError(f"请求最终失败: {last}")


def post_form_with_retry(url: str, data: dict, tries: int = 8, timeout: int = 25) -> dict:
    """POST form-encoded，带重试。"""
    last = ""
    body = urllib.parse.urlencode(data).encode()
    for i in range(tries):
        try:
            req = urllib.request.Request(
                url,
                data=body,
                headers={
                    "Accept": "application/json",
                    "Content-Type": "application/x-www-form-urlencoded",
                    "User-Agent": "wb-oauth",
                },
            )
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                return json.loads(resp.read().decode())
        except Exception as exc:  # noqa: BLE001
            last = f"{type(exc).__name__}: {exc}"
            wait = min(2 ** i, 15)
            print(f"  [重试 {i + 1}/{tries}] {last} → {wait}s 后再试")
            time.sleep(wait)
    raise RuntimeError(f"请求最终失败: {last}")


def main() -> int:
    port = DEFAULT_PORT
    if "--port" in sys.argv:
        port = int(sys.argv[sys.argv.index("--port") + 1])

    result: dict = {}

    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):  # noqa: N802
            params = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            if "code" in params:
                result["code"] = params["code"][0]
                body = (
                    "<html><meta charset='utf-8'><body style='font-family:sans-serif;"
                    "padding:40px'><h2>授权成功</h2>"
                    "<p>可以关闭这个页面，回到命令行继续。</p></body></html>"
                ).encode()
                status = 200
            elif "error" in params:
                result["error"] = params["error"][0]
                body = b"<html><body>authorization failed</body></html>"
                status = 400
            else:
                body = b"<html><body>waiting</body></html>"
                status = 202
            self.send_response(status)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            if status != 202:
                threading.Thread(target=server.shutdown, daemon=True).start()

        def log_message(self, *args):
            pass

    server = http.server.HTTPServer(("127.0.0.1", port), Handler)
    server.timeout = 600
    threading.Thread(target=server.serve_forever, daemon=True).start()

    redirect = f"http://127.0.0.1:{port}/"
    auth_url = (
        "https://github.com/login/oauth/authorize"
        f"?client_id={urllib.parse.quote(CLIENT_ID)}"
        f"&scope={urllib.parse.quote(SCOPE)}"
        f"&redirect_uri={urllib.parse.quote(redirect)}"
    )

    print("=" * 70, flush=True)
    print("请在浏览器打开下面链接，登录 captionteemo 并点击授权：", flush=True)
    print(flush=True)
    print(auth_url, flush=True)
    print(flush=True)
    print(f"等待回调中（本地端口 {port}，最长 10 分钟）...", flush=True)
    print("=" * 70, flush=True)

    deadline = 600
    while deadline > 0 and "code" not in result and "error" not in result:
        server.handle_request()
        deadline -= 1

    if "error" in result:
        print(f"授权被拒绝: {result['error']}")
        return 1
    if "code" not in result:
        print("超时：未收到回调。")
        return 1

    print("收到授权码，正在换取 token...", flush=True)
    payload = post_form_with_retry(
        "https://github.com/login/oauth/access_token",
        {
            "client_id": CLIENT_ID,
            "client_secret": CLIENT_SECRET,
            "code": result["code"],
            "redirect_uri": redirect,
        },
    )
    if "access_token" not in payload:
        print(f"换取 token 失败: {payload}")
        return 1

    print()
    print("TOKEN_START")
    print(payload["access_token"])
    print("TOKEN_END")
    return 0


if __name__ == "__main__":
    sys.exit(main())
