#!/usr/bin/env python3
"""Minimal RCON client for the local smoke server. Env: RCON_HOST (127.0.0.1), RCON_PORT (25621), RCON_PASSWORD."""
import os
import socket
import struct
import sys


def _packet(req_id, kind, body):
    b = body.encode() + b"\0\0"
    return struct.pack("<iii", len(b) + 8, req_id, kind) + b


def _read(s):
    head = b""
    while len(head) < 4:
        chunk = s.recv(4 - len(head))
        if not chunk:
            raise ConnectionError("RCON connection closed")
        head += chunk
    n = struct.unpack("<i", head)[0]
    data = b""
    while len(data) < n:
        chunk = s.recv(n - len(data))
        if not chunk:
            raise ConnectionError("RCON connection closed")
        data += chunk
    return data[8:-2].decode(errors="replace")


def run(*commands):
    host = os.environ.get("RCON_HOST", "127.0.0.1")
    port = int(os.environ.get("RCON_PORT", "25621"))
    with socket.create_connection((host, port), timeout=30) as s:
        s.sendall(_packet(1, 3, os.environ["RCON_PASSWORD"]))
        _read(s)
        out = []
        for c in commands:
            s.sendall(_packet(2, 2, c))
            out.append(_read(s))
        return out


if __name__ == "__main__":
    for line in run(*sys.argv[1:]):
        print(line)
