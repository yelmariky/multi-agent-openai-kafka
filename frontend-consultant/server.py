#!/usr/bin/env python3
"""SPA server — serves index.html for any path that isn't a real file."""
import http.server
import os
import sys

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 3001


class SPAHandler(http.server.SimpleHTTPRequestHandler):
    def do_GET(self):
        path = self.translate_path(self.path)
        if os.path.isfile(path):
            return super().do_GET()
        self.path = '/index.html'
        return super().do_GET()

    def log_message(self, fmt, *args):
        # Intentionally silent: access log noise is not useful in dev.
        pass

    def handle_error(self, request, client_address):
        # Intentionally silent: BrokenPipeError happens when the browser cancels
        # a request mid-flight (tab closed, reload) — not an application error.
        pass


with http.server.HTTPServer(('', PORT), SPAHandler) as httpd:
    print(f'Consultant console  →  http://localhost:{PORT}/')
    httpd.serve_forever()
