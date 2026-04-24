"""
Local HTTP receiver for the YT Tile Scraper extension.

Run:        python yt_receiver.py [--port 8765] [--out tiles.jsonl] [--no-dedup]
Endpoint:   POST http://127.0.0.1:8765/tiles  with JSON body {"tiles": [...], "page_url": "..."}
Output:     each newly-seen tile is written as one NDJSON line to stdout (or --out file).
"""

from __future__ import annotations

import argparse
import json
import sys
import threading
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import IO, Any


def iso_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


class State:
    def __init__(self, out_fh: IO[str], dedup: bool) -> None:
        self.out_fh = out_fh
        self.dedup = dedup
        self.seen: set[str] = set()
        self.lock = threading.Lock()
        self.total_received = 0
        self.total_accepted = 0


def make_handler(state: State):
    class Handler(BaseHTTPRequestHandler):
        def _cors(self) -> None:
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Access-Control-Allow-Methods", "POST, OPTIONS")
            self.send_header("Access-Control-Allow-Headers", "Content-Type")

        def do_OPTIONS(self) -> None:  # noqa: N802
            self.send_response(204)
            self._cors()
            self.end_headers()

        def do_GET(self) -> None:  # noqa: N802
            if self.path in ("/", "/health"):
                body = json.dumps({
                    "ok": True,
                    "received": state.total_received,
                    "accepted": state.total_accepted,
                    "unique": len(state.seen),
                }).encode("utf-8")
                self.send_response(200)
                self._cors()
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return
            self.send_response(404)
            self._cors()
            self.end_headers()

        def do_POST(self) -> None:  # noqa: N802
            if self.path != "/tiles":
                self.send_response(404)
                self._cors()
                self.end_headers()
                return
            length = int(self.headers.get("Content-Length", "0") or 0)
            raw = self.rfile.read(length) if length > 0 else b""
            try:
                payload: dict[str, Any] = json.loads(raw.decode("utf-8") or "{}")
            except json.JSONDecodeError as e:
                self._reply(400, {"ok": False, "error": f"bad json: {e}"})
                return

            tiles = payload.get("tiles") or []
            if not isinstance(tiles, list):
                self._reply(400, {"ok": False, "error": "tiles must be an array"})
                return

            received_at = iso_now()
            accepted = 0
            with state.lock:
                state.total_received += len(tiles)
                for t in tiles:
                    if not isinstance(t, dict):
                        continue
                    key = (t.get("video_id") or "").strip() or (t.get("url") or "").strip()
                    if state.dedup:
                        if not key or key in state.seen:
                            continue
                        state.seen.add(key)
                    t.setdefault("scraped_at", received_at)
                    t["received_at"] = received_at
                    state.out_fh.write(json.dumps(t, ensure_ascii=False) + "\n")
                    accepted += 1
                state.out_fh.flush()
                state.total_accepted += accepted

            print(f"[receiver] received={len(tiles)} accepted={accepted} "
                  f"total_unique={len(state.seen)}", file=sys.stderr)
            self._reply(200, {"ok": True, "received": len(tiles), "accepted": accepted})

        def _reply(self, status: int, obj: dict) -> None:
            body = json.dumps(obj).encode("utf-8")
            self.send_response(status)
            self._cors()
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *_args, **_kwargs) -> None:
            # Silence default access log; we print our own.
            pass

    return Handler


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Receive YouTube tile records from the YT Tile Scraper extension.")
    p.add_argument("--host", default="127.0.0.1", help="Bind host (default 127.0.0.1).")
    p.add_argument("--port", type=int, default=8765, help="Bind port (default 8765).")
    p.add_argument("--out", default=None, help="Append NDJSON to this file instead of stdout.")
    p.add_argument("--no-dedup", action="store_true", help="Do not dedupe by video_id (write every tile).")
    return p.parse_args(argv)


def main() -> int:
    args = parse_args()
    out_fh: IO[str] = open(args.out, "a", encoding="utf-8") if args.out else sys.stdout
    state = State(out_fh, dedup=not args.no_dedup)
    server = ThreadingHTTPServer((args.host, args.port), make_handler(state))
    print(f"[receiver] listening on http://{args.host}:{args.port}/tiles "
          f"(dedup={'on' if state.dedup else 'off'}, out={'stdout' if out_fh is sys.stdout else args.out})",
          file=sys.stderr)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("[receiver] stopping...", file=sys.stderr)
    finally:
        server.server_close()
        if out_fh is not sys.stdout:
            out_fh.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
