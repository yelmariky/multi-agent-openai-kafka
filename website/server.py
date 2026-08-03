#!/usr/bin/env python3
"""Static server for the IA Insight marketing site."""
import http.server
import os
import sys

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 3003


class SiteHandler(http.server.SimpleHTTPRequestHandler):
    def do_GET(self):
        path = self.translate_path(self.path)
        if os.path.isfile(path):
            return super().do_GET()
        self.path = '/index.html'
        return super().do_GET()

    def log_message(self, fmt, *args):
        pass

    def handle_error(self, request, client_address):
        pass


with http.server.HTTPServer(('', PORT), SiteHandler) as httpd:
    print(f'Site vitrine  →  http://localhost:{PORT}/')
    httpd.serve_forever()
