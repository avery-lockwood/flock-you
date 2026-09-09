"""
companion.py — sync endpoint for the FlockYou phone companion (Android app
or web logger). The phone batches its GPS track and geotagged events
(detections relayed over BLE, manual camera marks) and POSTs them here
whenever it has connectivity to the tailnet.

Wire format, POST /api/companion/sync:
    {
      "device": "moto-g",                     # free-form client name
      "track":  [{"t": ms_epoch, "lat": .., "lon": .., "acc": ..}, ...],
      "events": [{"kind": "det"|"mark", "t": ms_epoch,
                  "fix": {"lat": .., "lon": .., "acc": ..} | null,
                  "data": {...raw device JSON...}}, ...]
    }
Both arrays may be empty; the client sends only entries it hasn't synced
yet. Everything is appended to per-day JSONL files under companion_data/,
one JSON object per line with a "received_at" stamp added.

    GET /api/companion/status      -> stored line counts per file
    GET /api/companion/export.gpx  -> everything merged as one GPX
"""

from flask import Blueprint, jsonify, request, Response
from datetime import datetime, timezone
import json
import os
import threading

bp = Blueprint("companion", __name__)

DATA_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "companion_data")

_write_lock = threading.Lock()


def _append(kind, rows):
    if not rows:
        return 0
    os.makedirs(DATA_DIR, exist_ok=True)
    day = datetime.now(timezone.utc).strftime("%Y%m%d")
    path = os.path.join(DATA_DIR, f"{kind}-{day}.jsonl")
    stamp = datetime.now(timezone.utc).isoformat()
    written = 0
    with _write_lock, open(path, "a", encoding="utf-8") as f:
        for row in rows:
            if not isinstance(row, dict):
                continue
            row = dict(row)
            row["received_at"] = stamp
            f.write(json.dumps(row, separators=(",", ":")) + "\n")
            written += 1
    return written


@bp.route("/api/companion/sync", methods=["POST"])
def companion_sync():
    body = request.get_json(silent=True)
    if not isinstance(body, dict):
        return jsonify({"error": "expected a JSON object"}), 400
    device = str(body.get("device", ""))[:64]
    track = body.get("track") or []
    events = body.get("events") or []
    if not isinstance(track, list) or not isinstance(events, list):
        return jsonify({"error": "track and events must be arrays"}), 400
    for row in track:
        if isinstance(row, dict):
            row["device"] = device
    for row in events:
        if isinstance(row, dict):
            row["device"] = device
    n_track = _append("track", track)
    n_events = _append("events", events)
    return jsonify({"ok": True, "stored_track": n_track, "stored_events": n_events})


@bp.route("/api/companion/status")
def companion_status():
    files = {}
    if os.path.isdir(DATA_DIR):
        for name in sorted(os.listdir(DATA_DIR)):
            if name.endswith(".jsonl"):
                with open(os.path.join(DATA_DIR, name), encoding="utf-8") as f:
                    files[name] = sum(1 for _ in f)
    return jsonify({"files": files})


def _iter_rows(prefix):
    if not os.path.isdir(DATA_DIR):
        return
    for name in sorted(os.listdir(DATA_DIR)):
        if not (name.startswith(prefix) and name.endswith(".jsonl")):
            continue
        with open(os.path.join(DATA_DIR, name), encoding="utf-8") as f:
            for line in f:
                try:
                    yield json.loads(line)
                except ValueError:
                    continue


def _xml_escape(s):
    return (str(s).replace("&", "&amp;").replace("<", "&lt;").replace('"', "&quot;"))


@bp.route("/api/companion/export.gpx")
def companion_export_gpx():
    def iso(ms):
        return datetime.fromtimestamp(ms / 1000, tz=timezone.utc).strftime(
            "%Y-%m-%dT%H:%M:%SZ")

    parts = ['<?xml version="1.0" encoding="UTF-8"?>',
             '<gpx version="1.1" creator="flock-you companion" '
             'xmlns="http://www.topografix.com/GPX/1/1">']
    for e in _iter_rows("events"):
        fix = e.get("fix")
        if not fix:
            continue
        data = e.get("data") or {}
        if e.get("kind") == "mark":
            name, desc = "MARK", "manual camera sighting"
        else:
            name = f"{data.get('method', 'det')} t{data.get('tier', '')}"
            desc = (f"mac={data.get('mac', '')} rssi={data.get('rssi', '')} "
                    f"ch={data.get('ch', '')} ssid={data.get('ssid', '')}")
        parts.append(
            f'<wpt lat="{fix.get("lat")}" lon="{fix.get("lon")}">'
            f'<time>{iso(e.get("t", 0))}</time>'
            f'<name>{_xml_escape(name)}</name>'
            f'<desc>{_xml_escape(desc)}</desc></wpt>')
    parts.append('<trk><name>companion track</name><trkseg>')
    for p in sorted(_iter_rows("track"), key=lambda r: r.get("t", 0)):
        if "lat" in p and "lon" in p:
            parts.append(f'<trkpt lat="{p["lat"]}" lon="{p["lon"]}">'
                         f'<time>{iso(p.get("t", 0))}</time></trkpt>')
    parts.append("</trkseg></trk></gpx>")
    return Response("\n".join(parts), mimetype="application/gpx+xml",
                    headers={"Content-Disposition":
                             "attachment; filename=companion.gpx"})
