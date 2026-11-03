"""Offline protocol fixture. Fixed port preserves index identity across restarts.

No Chat answer is fabricated. Vectors/scores are synthetic and prove no AI quality.
"""
import http.server
import json
import sys
from pathlib import Path

folder, port = Path(sys.argv[1]), int(sys.argv[2])
counts = {"embedding": 0, "rerank": 0, "chat_unavailable": 0}


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_POST(self):
        status = 200
        try:
            size = int(self.headers.get("Content-Length", "0"))
            if not 0 < size <= 100000:
                raise ValueError()
            body = json.loads(self.rfile.read(size))
            if self.path == "/embeddings":
                assert body["dimensions"] == 1024 and 1 <= len(body["input"]) <= 8
                counts["embedding"] += 1
                result = {"data": [{"index": i, "embedding": [1.0] + [0.0] * 1023}
                                   for i in range(len(body["input"]))]}
            elif self.path == "/rerank":
                assert 1 <= len(body["input"]["documents"]) <= 20
                counts["rerank"] += 1
                result = {"output": {"results": [{"index": i, "relevance_score": .95 - i * .01}
                          for i in range(len(body["input"]["documents"]))]}}
            else:
                counts["chat_unavailable"] += 1
                raise ValueError()
        except (ValueError, KeyError, AssertionError):
            status, result = 503, {"code": "OFFLINE_PROTOCOL_NO_REAL_CHAT"}
        data = json.dumps(result).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)
        (folder / "protocol-counts.json").write_text(json.dumps({"external_calls": 0, **counts}), "utf-8")


http.server.ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
