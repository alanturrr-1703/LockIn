"""
YouTube live-DOM scraper. Default: copies your real Chrome profile to a sandbox and auto-launches.
Install:  pip install playwright && playwright install chromium
Default:  python yt_scraper.py --ndjson           (sandbox copy of default profile, opens youtube.com)
Options:  --url URL  --watch [N]  --strict|--all  --lookahead N  --out FILE  --keep-running
Attach:   python yt_scraper.py --cdp-url http://127.0.0.1:9222   (use existing CDP Chrome)
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import shutil
import signal
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from playwright.async_api import Page, async_playwright

PROFILE_CACHE_DIR = Path(__file__).resolve().parent / ".profile-cache"

PROFILE_IGNORE_NAMES = {
    "Singleton", "SingletonCookie", "SingletonLock", "SingletonSocket",
    "lockfile", "LOCK", "parent.lock",
    "Cache", "Code Cache", "GPUCache", "ShaderCache", "GrShaderCache",
    "CacheStorage", "ScriptCache", "Crashpad", "component_crx_cache",
    "optimization_guide_model_store", "Safe Browsing", "SafetyTips",
    "Subresource Filter", "ZxcvbnData",
}

COOKIE_FILES = ["Cookies", "Cookies-journal"]

TILE_SELECTORS = [
    "ytd-rich-item-renderer",
    "ytd-video-renderer",
    "ytd-grid-video-renderer",
    "ytd-compact-video-renderer",
    "ytd-playlist-video-renderer",
    "ytd-reel-item-renderer",
]

# Runs inside the page. Returns the array of tile dicts.
EXTRACT_JS = r"""
({ selectors, lookahead, mode }) => {
    const out = [];

    const TITLE_SEL = [
        "#video-title-link",
        "a#video-title",
        "#video-title",
        "h3 a#video-title-link",
        "h3 #video-title",
        "yt-formatted-string#video-title",
        "a.yt-lockup-metadata-view-model-wiz__title",
    ];
    const CHANNEL_SEL = [
        "ytd-channel-name #text a",
        "ytd-channel-name #text",
        "ytd-channel-name a",
        "#channel-name #text",
        "#channel-name a",
        "#byline a",
        "#byline",
        ".ytd-channel-name",
    ];
    const DESC_SEL = [
        "#description-text",
        "yt-formatted-string#description-text",
        ".metadata-snippet-text",
        "#description",
    ];

    const firstText = (el, list) => {
        for (const sel of list) {
            const node = el.querySelector(sel);
            if (!node) continue;
            const t = (node.getAttribute && node.getAttribute("title")) || node.textContent || "";
            const cleaned = t.replace(/\s+/g, " ").trim();
            if (cleaned) return cleaned;
        }
        return "";
    };

    const firstHref = (el) => {
        const cand = el.querySelector(
            "a#thumbnail[href], a#video-title-link[href], a#video-title[href], a.yt-lockup-metadata-view-model-wiz__title[href], a[href*='/watch?v='], a[href*='/shorts/']"
        );
        if (!cand) return "";
        const h = cand.getAttribute("href") || "";
        if (!h) return "";
        try { return new URL(h, location.origin).toString(); }
        catch (_) { return h; }
    };

    const videoIdFromUrl = (url) => {
        if (!url) return "";
        try {
            const u = new URL(url);
            const v = u.searchParams.get("v");
            if (v) return v;
            const m = u.pathname.match(/\/shorts\/([^/?#]+)/);
            if (m) return m[1];
        } catch (_) {}
        return "";
    };

    const firstThumb = (el) => {
        const img = el.querySelector("img#img, img.yt-core-image, img");
        if (!img) return "";
        return img.getAttribute("src") || img.getAttribute("data-thumb") || "";
    };

    const vpH = window.innerHeight || document.documentElement.clientHeight;
    const seen = new Set();

    for (const sel of selectors) {
        const nodes = document.querySelectorAll(sel);
        for (const el of nodes) {
            if (seen.has(el)) continue;
            seen.add(el);

            const rect = el.getBoundingClientRect();
            if (rect.width === 0 && rect.height === 0) continue;

            let include = true;
            if (mode === "strict") {
                include = rect.bottom > 0 && rect.top < vpH;
            } else if (mode === "lookahead") {
                include = rect.bottom > 0 && rect.top < vpH + lookahead;
            } else if (mode === "all") {
                include = true;
            }
            if (!include) continue;

            const title = firstText(el, TITLE_SEL);
            const channel = firstText(el, CHANNEL_SEL);
            if (!title && !channel) continue; // skeleton

            const url = firstHref(el);
            const description = firstText(el, DESC_SEL);
            const thumbnail_url = firstThumb(el);
            const video_id = videoIdFromUrl(url);

            out.push({
                video_id,
                title,
                channel,
                description,
                thumbnail_url,
                url,
                tile_type: sel,
            });
        }
    }

    return out;
}
"""


def iso_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def find_chrome_exe(override: str | None = None) -> Path:
    if override:
        p = Path(override)
        if p.exists():
            return p
        raise FileNotFoundError(f"--chrome-path does not exist: {override}")
    candidates = [
        Path(r"C:\Program Files\Google\Chrome\Application\chrome.exe"),
        Path(r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe"),
        Path(os.environ.get("LOCALAPPDATA", "")) / "Google/Chrome/Application/chrome.exe",
    ]
    for c in candidates:
        if c.exists():
            return c
    tried = "\n  ".join(str(c) for c in candidates)
    raise FileNotFoundError(f"Could not find chrome.exe. Tried:\n  {tried}\nUse --chrome-path.")


def default_profile_dir(override: str | None = None) -> Path:
    if override:
        p = Path(override)
        if p.exists():
            return p
        raise FileNotFoundError(f"--profile-dir does not exist: {override}")
    local = os.environ.get("LOCALAPPDATA")
    if not local:
        raise RuntimeError("LOCALAPPDATA is not set; cannot locate default Chrome profile.")
    p = Path(local) / "Google/Chrome/User Data"
    if not p.exists():
        raise FileNotFoundError(f"Default Chrome profile not found at {p}. Use --profile-dir.")
    return p


def _ignore_profile(_dir: str, names: list[str]) -> set[str]:
    return {n for n in names if n in PROFILE_IGNORE_NAMES}


def prepare_sandbox_profile(src: Path, dest: Path, fresh: bool) -> Path:
    if fresh and dest.exists():
        shutil.rmtree(dest, ignore_errors=True)
    if not dest.exists():
        print(f"[info] copying profile {src} -> {dest} (one-time, may take a minute)", file=sys.stderr)
        shutil.copytree(src, dest, ignore=_ignore_profile, dirs_exist_ok=True)
    # Always refresh cookies so login stays current.
    src_default = src / "Default"
    dest_default = dest / "Default"
    dest_default.mkdir(parents=True, exist_ok=True)
    for name in COOKIE_FILES:
        s = src_default / name
        if s.exists():
            try:
                shutil.copy2(s, dest_default / name)
            except (PermissionError, OSError) as e:
                print(f"[warn] could not refresh {name}: {e}", file=sys.stderr)
    return dest


def pick_free_port(preferred: int) -> int:
    for port in range(preferred, preferred + 50):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            try:
                s.bind(("127.0.0.1", port))
                return port
            except OSError:
                continue
    raise RuntimeError(f"No free port found in {preferred}..{preferred + 49}")


def launch_chrome(chrome: Path, user_data_dir: Path, port: int, url: str) -> subprocess.Popen:
    args = [
        str(chrome),
        f"--remote-debugging-port={port}",
        f"--user-data-dir={user_data_dir}",
        "--no-first-run",
        "--no-default-browser-check",
        "--restore-last-session=false",
        url,
    ]
    creationflags = 0
    if sys.platform == "win32":
        creationflags = getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0)
    return subprocess.Popen(args, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                            creationflags=creationflags)


def wait_for_cdp(port: int, timeout: float = 15.0) -> str:
    url = f"http://127.0.0.1:{port}/json/version"
    deadline = time.time() + timeout
    last_err: Exception | None = None
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(url, timeout=1.0) as resp:
                if resp.status == 200:
                    return f"http://127.0.0.1:{port}"
        except (urllib.error.URLError, ConnectionError, OSError) as e:
            last_err = e
        time.sleep(0.2)
    raise TimeoutError(f"CDP did not come up on port {port} within {timeout}s: {last_err}")


async def ensure_target_tab(browser, target_url: str | None) -> Page:
    try:
        return await find_youtube_page(browser)
    except RuntimeError:
        if not target_url:
            raise
        ctx = browser.contexts[0] if browser.contexts else await browser.new_context()
        page = await ctx.new_page()
        await page.goto(target_url, wait_until="domcontentloaded")
        print(f"[info] opened new tab: {target_url}", file=sys.stderr)
        return page


async def find_youtube_page(browser) -> Page:
    candidates: list[tuple[Page, int]] = []
    for ctx in browser.contexts:
        for page in ctx.pages:
            url = page.url or ""
            if "youtube.com" not in url:
                continue
            try:
                has_focus = await page.evaluate("() => document.hasFocus()")
                visible = await page.evaluate("() => document.visibilityState === 'visible'")
            except Exception:
                has_focus = False
                visible = False
            score = (2 if has_focus else 0) + (1 if visible else 0)
            candidates.append((page, score))

    if not candidates:
        raise RuntimeError("No open YouTube tab found in the attached Chrome instance.")

    candidates.sort(key=lambda t: t[1], reverse=True)
    page = candidates[0][0]
    print(f"[info] attached to YouTube tab: {page.url}", file=sys.stderr)
    print(f"[info] YouTube tabs seen: {len(candidates)}", file=sys.stderr)
    return page


async def extract_tiles(page: Page, mode: str, lookahead: int) -> list[dict[str, Any]]:
    tiles = await page.evaluate(
        EXTRACT_JS,
        {"selectors": TILE_SELECTORS, "lookahead": lookahead, "mode": mode},
    )
    ts = iso_now()
    for t in tiles:
        t["scraped_at"] = ts
    return tiles


def emit(records: list[dict[str, Any]], ndjson: bool, out_fh) -> None:
    if ndjson:
        for r in records:
            out_fh.write(json.dumps(r, ensure_ascii=False) + "\n")
    else:
        out_fh.write(json.dumps(records, ensure_ascii=False, indent=2) + "\n")
    out_fh.flush()


async def run(args: argparse.Namespace) -> int:
    if args.strict:
        mode = "strict"
    elif args.all:
        mode = "all"
    else:
        mode = "lookahead"

    chrome_proc: subprocess.Popen | None = None
    cdp_url = args.cdp_url

    try:
        if cdp_url is None:
            chrome_exe = find_chrome_exe(args.chrome_path)
            print(f"[info] chrome: {chrome_exe}", file=sys.stderr)
            src_profile = default_profile_dir(args.profile_dir)
            sandbox = prepare_sandbox_profile(src_profile, PROFILE_CACHE_DIR, args.fresh_profile)
            print(f"[info] sandbox profile: {sandbox}", file=sys.stderr)
            port = pick_free_port(args.port)
            print(f"[info] launching Chrome on port {port}", file=sys.stderr)
            chrome_proc = launch_chrome(chrome_exe, sandbox, port, args.url)
            try:
                cdp_url = wait_for_cdp(port)
            except TimeoutError as e:
                print(f"ERROR: {e}", file=sys.stderr)
                return 4
            print(f"[info] CDP ready at {cdp_url}", file=sys.stderr)
    except (FileNotFoundError, RuntimeError) as e:
        print(f"ERROR: {e}", file=sys.stderr)
        return 5

    async with async_playwright() as pw:
        try:
            browser = await pw.chromium.connect_over_cdp(cdp_url)
        except Exception as e:
            print(
                f"ERROR: could not attach to Chrome over CDP at {cdp_url}.\n"
                f"Underlying error: {e}",
                file=sys.stderr,
            )
            _terminate(chrome_proc)
            return 2

        try:
            page = await ensure_target_tab(browser, args.url if args.cdp_url is None else None)
        except RuntimeError as e:
            print(f"ERROR: {e}", file=sys.stderr)
            _terminate(chrome_proc)
            return 3

        out_fh = open(args.out, "w", encoding="utf-8") if args.out else sys.stdout

        stop = asyncio.Event()

        def _stop(*_a):
            stop.set()

        try:
            signal.signal(signal.SIGINT, _stop)
        except (ValueError, OSError):
            pass

        try:
            if not args.watch:
                tiles = await extract_tiles(page, mode, args.lookahead)
                print(f"[info] mode={mode} lookahead={args.lookahead} tiles_found={len(tiles)}",
                      file=sys.stderr)
                emit(tiles, args.ndjson, out_fh)
                return 0

            seen_ids: set[str] = set()
            interval = max(0.5, float(args.watch))

            while not stop.is_set():
                try:
                    tiles = await extract_tiles(page, mode, args.lookahead)
                except Exception as e:
                    print(f"[warn] extract failed: {e}", file=sys.stderr)
                    tiles = []

                fresh: list[dict[str, Any]] = []
                for t in tiles:
                    vid = t.get("video_id") or ""
                    if not vid:
                        # fall back to url for items without a parseable id
                        vid = t.get("url") or ""
                    if not vid or vid in seen_ids:
                        continue
                    seen_ids.add(vid)
                    fresh.append(t)

                if fresh:
                    # In watch mode, always stream line-delimited so the pipeline keeps flowing.
                    emit(fresh, ndjson=True, out_fh=out_fh)

                try:
                    await asyncio.wait_for(stop.wait(), timeout=interval)
                except asyncio.TimeoutError:
                    pass

            return 0
        finally:
            if out_fh is not sys.stdout:
                out_fh.close()
            try:
                await browser.close()
            except Exception:
                pass
            if not args.keep_running:
                _terminate(chrome_proc)


def _terminate(proc: subprocess.Popen | None) -> None:
    if proc is None:
        return
    if proc.poll() is not None:
        return
    try:
        proc.terminate()
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            proc.kill()
    except Exception:
        pass


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    p = argparse.ArgumentParser(
        description="Scrape YouTube tiles. Default: copy your real Chrome profile to a sandbox and auto-launch."
    )
    vis = p.add_mutually_exclusive_group()
    vis.add_argument("--strict", action="store_true", help="Only literally visible tiles.")
    vis.add_argument("--all", action="store_true", help="Every tile currently materialized in DOM.")
    p.add_argument("--lookahead", type=int, default=3000, help="Pixels below fold to include (default 3000).")
    p.add_argument("--watch", nargs="?", const=2.0, type=float, default=None,
                   metavar="SECONDS", help="Poll every N seconds (default 2). Dedupes by video_id.")
    p.add_argument("--ndjson", action="store_true", help="Newline-delimited JSON output.")
    p.add_argument("--out", type=str, default=None, help="Write to FILE instead of stdout.")
    p.add_argument("--url", type=str, default="https://www.youtube.com",
                   help="URL to open in the sandbox Chrome (default: youtube.com).")
    p.add_argument("--cdp-url", type=str, default=None,
                   help="Skip auto-launch and attach to an existing CDP Chrome at this URL.")
    p.add_argument("--port", type=int, default=9222, help="Preferred CDP port for the sandbox launch.")
    p.add_argument("--chrome-path", type=str, default=None, help="Override Chrome executable path.")
    p.add_argument("--profile-dir", type=str, default=None,
                   help="Override source profile dir (default: %%LOCALAPPDATA%%/Google/Chrome/User Data).")
    p.add_argument("--fresh-profile", action="store_true",
                   help="Re-copy the profile from scratch instead of reusing the cached copy.")
    p.add_argument("--keep-running", action="store_true",
                   help="Do not close the sandbox Chrome on exit.")
    return p.parse_args(argv)


def main() -> int:
    args = parse_args()
    try:
        return asyncio.run(run(args))
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    sys.exit(main())
