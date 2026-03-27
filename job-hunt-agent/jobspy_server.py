"""
jobspy_server.py  —  JobSpy Flask sidecar for Job Hunt Agent
=============================================================

This is the PERMANENT FIX for:
  - Naukri returning empty page (JS-rendered, bot-blocked)
  - Indeed 403
  - Instahyre 404 API

JobSpy uses real browser automation internally to bypass all of these.

SETUP (run these once in your terminal):
    pip install python-jobspy flask

RUN (keep this terminal open alongside Spring Boot):
    python jobspy_server.py

Verify it works:
    curl http://localhost:5001/health
    curl "http://localhost:5001/search?keyword=Java+Backend&location=Bangalore&results=5"

Spring Boot auto-detects this on port 5001 and routes all scraping through it.
You will see in the Spring Boot logs:
    "JobSpy sidecar detected — using it (bypasses all 403s)"
"""

import sys
import json
import traceback
from datetime import datetime

# ── Verify dependencies before starting ──────────────────────────────────────
def check_dependencies():
    missing = []
    try:
        import jobspy
    except ImportError:
        missing.append("python-jobspy")
    try:
        import flask
    except ImportError:
        missing.append("flask")

    if missing:
        print("\n" + "="*55)
        print("  MISSING PACKAGES — run this command first:")
        print(f"\n  pip install {' '.join(missing)}\n")
        print("="*55 + "\n")
        sys.exit(1)

check_dependencies()

from flask import Flask, request, jsonify
from jobspy import scrape_jobs
import pandas as pd

app = Flask(__name__)

# ── Config ────────────────────────────────────────────────────────────────────
DEFAULT_SITES    = ["naukri", "linkedin"]   # indeed/glassdoor slow — add back if needed
DEFAULT_LOCATION = "Bangalore, India"
DEFAULT_RESULTS  = 15
MAX_RESULTS      = 30
MAX_HOURS_OLD    = 72   # only jobs from last 3 days


def safe_str(val):
    """Convert any value to a clean string, handling NaN/None."""
    if val is None:
        return ""
    try:
        import math
        if isinstance(val, float) and math.isnan(val):
            return ""
    except Exception:
        pass
    s = str(val).strip()
    return "" if s.lower() in ("nan", "none", "null") else s


def job_to_dict(row):
    """Convert a DataFrame row to a JSON-safe dict."""
    return {
        "title":       safe_str(row.get("title")),
        "company":     safe_str(row.get("company")),
        "location":    safe_str(row.get("location")),
        "site":        safe_str(row.get("site")).upper(),
        "job_url":     safe_str(row.get("job_url")),
        "description": safe_str(row.get("description"))[:5000],
        "min_amount":  safe_str(row.get("min_amount")),
        "max_amount":  safe_str(row.get("max_amount")),
        "currency":    safe_str(row.get("currency")),
        "date_posted": safe_str(row.get("date_posted")),
        "job_type":    safe_str(row.get("job_type")),
        "is_remote":   bool(row.get("is_remote", False)),
    }


# ── Routes ────────────────────────────────────────────────────────────────────

@app.route("/health")
def health():
    return jsonify({
        "status":  "UP",
        "service": "JobSpy sidecar",
        "time":    datetime.now().isoformat(),
        "sites":   DEFAULT_SITES
    })


@app.route("/search")
def search():
    keyword  = request.args.get("keyword", "Senior Backend Engineer Java").strip()
    location = request.args.get("location", DEFAULT_LOCATION).strip()
    results  = min(int(request.args.get("results", DEFAULT_RESULTS)), MAX_RESULTS)

    # Comma-separated list of sites, or default
    sites_param = request.args.get("sites", ",".join(DEFAULT_SITES))
    sites = [s.strip().lower() for s in sites_param.split(",") if s.strip()]

    print(f"\n[{datetime.now().strftime('%H:%M:%S')}] Search: '{keyword}' | '{location}' | sites={sites} | n={results}")

    all_jobs = []
    errors   = {}

    # Scrape each site individually so one failure doesn't kill the others
    for site in sites:
        try:
            print(f"  Scraping {site}...", end=" ", flush=True)
            df = scrape_jobs(
                site_name              = [site],
                search_term            = keyword,
                location               = location,
                results_wanted         = results,
                hours_old              = MAX_HOURS_OLD,
                country_indeed         = "India",
                linkedin_fetch_description = (site == "linkedin"),
                verbose                = 0,
            )

            if df is None or df.empty:
                print(f"0 results")
                continue

            # Filter out rows without a job URL
            df = df[df["job_url"].notna() & (df["job_url"] != "")]
            count = len(df)
            print(f"{count} jobs")

            for _, row in df.iterrows():
                job = job_to_dict(row)
                if job["job_url"]:
                    all_jobs.append(job)

        except Exception as e:
            msg = str(e)
            errors[site] = msg
            print(f"ERROR: {msg[:80]}")
            traceback.print_exc()

    # Deduplicate by job_url
    seen = set()
    unique_jobs = []
    for j in all_jobs:
        if j["job_url"] not in seen:
            seen.add(j["job_url"])
            unique_jobs.append(j)

    print(f"  Total unique jobs: {len(unique_jobs)}\n")

    response = {
        "jobs":    unique_jobs,
        "total":   len(unique_jobs),
        "keyword": keyword,
        "location": location,
    }
    if errors:
        response["errors"] = errors

    return jsonify(response)


@app.route("/search/naukri")
def search_naukri():
    """Dedicated Naukri endpoint — useful for testing."""
    keyword  = request.args.get("keyword", "Senior Java Backend Engineer")
    location = request.args.get("location", "Bangalore, India")
    results  = int(request.args.get("results", 20))

    try:
        df = scrape_jobs(
            site_name      = ["naukri"],
            search_term    = keyword,
            location       = location,
            results_wanted = results,
            hours_old      = MAX_HOURS_OLD,
            country_indeed = "India",
            verbose        = 0,
        )
        if df is None or df.empty:
            return jsonify({"jobs": [], "total": 0, "site": "naukri"})

        jobs = [job_to_dict(row) for _, row in df.iterrows() if row.get("job_url")]
        return jsonify({"jobs": jobs, "total": len(jobs), "site": "naukri"})

    except Exception as e:
        return jsonify({"error": str(e), "jobs": []}), 500


if __name__ == "__main__":
    print("\n" + "="*55)
    print("  JobSpy Sidecar  —  port 5001")
    print("  Supported sites: naukri, linkedin, indeed, glassdoor")
    print()
    print("  Test endpoints:")
    print("    curl http://localhost:5001/health")
    print('    curl "http://localhost:5001/search?keyword=Java+Kafka+Backend&location=Bangalore&results=5"')
    print("="*55 + "\n")

    app.run(host="0.0.0.0", port=5001, debug=False, threaded=True)
