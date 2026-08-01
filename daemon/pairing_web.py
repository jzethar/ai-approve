#!/usr/bin/env python3
"""Same as pairing.py, but serves the pairing QR as a local web page instead
of printing ANSI art to the terminal - handy if you'd rather open a browser
tab than remember where the script lives. Binds to 127.0.0.1 only, since the
page shows the pre-shared pairing token in the clear.
"""
import http.server
import json
import subprocess
import sys

import pairing

DEFAULT_PORT = 8765


def qr_svg(text):
    """Render `text` as an inline SVG QR code via `qrencode`, or None if it's not installed."""
    try:
        return subprocess.run(
            ["qrencode", "-t", "SVG", "-o", "-", text],
            capture_output=True,
            text=True,
            check=True,
        ).stdout
    except (FileNotFoundError, subprocess.CalledProcessError):
        return None


def render_page(payload):
    text = json.dumps(payload)
    svg = qr_svg(text)
    qr_html = svg or "<p><em>qrencode not installed - use the pairing code below instead.</em></p>"
    return f"""<!doctype html>
<html>
<head>
<meta charset="utf-8">
<title>phone-ai-approve pairing</title>
<style>
  body {{ font-family: system-ui, sans-serif; max-width: 480px; margin: 3rem auto; text-align: center; }}
  svg {{ width: 260px; height: 260px; }}
  code {{ display: block; word-break: break-all; background: #f0f0f0; padding: 0.75rem;
          border-radius: 6px; margin-top: 1rem; font-size: 0.85rem; }}
</style>
</head>
<body>
  <h2>Scan to pair phone-ai-approve</h2>
  {qr_html}
  <p>Or paste this code into the app:</p>
  <code>{text}</code>
</body>
</html>"""


class Handler(http.server.BaseHTTPRequestHandler):
    payload = None

    def do_GET(self):
        body = render_page(Handler.payload).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format, *args):
        pass  # keep the terminal quiet; the URL is already printed once at startup


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_PORT
    Handler.payload = pairing.generate_pairing()
    server = http.server.HTTPServer(("127.0.0.1", port), Handler)
    print(f"Pairing page: http://127.0.0.1:{port}  (Ctrl+C to stop)")
    print(f"Saved to {pairing.pairing_path()}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
