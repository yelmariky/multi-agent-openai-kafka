#!/usr/bin/env python3
"""
Script CI — Scan du golden dataset via LLM Guard API.

Usage :
    python llm_guard_scan.py --url http://localhost:8000 --dataset golden-dataset.json

Codes de sortie :
    0 — tous les fixtures ont le résultat attendu
    1 — au moins un fixture a échoué (faux négatif ou faux positif)
    2 — LLM Guard API inaccessible (CI dégradé, non bloquant si --soft)
"""
import argparse
import json
import sys
import time
import urllib.request
import urllib.error
from pathlib import Path


PROMPT_SCANNERS = ["PromptInjection", "Secrets", "TokenLimit"]
OUTPUT_SCANNERS = ["Relevance", "Sensitive"]

GREEN = "\033[92m"
RED   = "\033[91m"
YELLOW = "\033[93m"
RESET = "\033[0m"


def scan_prompt(url: str, prompt: str, timeout: int = 5) -> dict | None:
    payload = json.dumps({
        "prompt": prompt,
        "scanners": PROMPT_SCANNERS
    }).encode()
    req = urllib.request.Request(
        f"{url}/analyze/prompt",
        data=payload,
        headers={"Content-Type": "application/json"},
        method="POST"
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return json.loads(resp.read())
    except urllib.error.URLError as e:
        return None


def run_golden_dataset(url: str, dataset_path: str, soft: bool) -> int:
    data = json.loads(Path(dataset_path).read_text())
    fixtures = data["fixtures"]

    passed = 0
    failed = 0
    skipped = 0

    print(f"\n{'═' * 60}")
    print(f"  LLM Guard CI — Golden Dataset v{data['version']}")
    print(f"  API: {url}  |  Fixtures: {len(fixtures)}")
    print(f"{'═' * 60}\n")

    # Vérifier la disponibilité de l'API
    probe = scan_prompt(url, "test", timeout=3)
    if probe is None:
        msg = f"{YELLOW}⚠  LLM Guard API unavailable at {url}{RESET}"
        if soft:
            print(f"{msg} — soft mode, skipping all scans")
            return 0
        else:
            print(f"{msg} — HARD mode, failing build")
            return 2

    for fix in fixtures:
        fid    = fix["id"]
        label  = fix["label"]
        prompt = fix["prompt"]
        expect_valid = fix["expected_valid"]

        result = scan_prompt(url, prompt)
        if result is None:
            print(f"  {YELLOW}SKIP{RESET} [{fid}] {label} — API error")
            skipped += 1
            continue

        actual_valid = result.get("is_valid", True)

        if actual_valid == expect_valid:
            status = f"{GREEN}PASS{RESET}"
            passed += 1
            print(f"  {status} [{fid}] {label}")
        else:
            status = f"{RED}FAIL{RESET}"
            failed += 1
            worst = _worst_scanner(result.get("scanners", {}))
            direction = "faux négatif (menace non détectée)" if expect_valid is False else "faux positif (prompt sain bloqué)"
            print(f"  {status} [{fid}] {label}")
            print(f"         → {direction} | worst_scanner={worst}")

        time.sleep(0.1)  # Éviter le rate-limit de l'API locale

    print(f"\n{'─' * 60}")
    print(f"  Résultat : {passed} OK  |  {failed} ÉCHEC  |  {skipped} IGNORÉS")
    print(f"{'─' * 60}\n")

    return 1 if failed > 0 else 0


def _worst_scanner(scanners: dict) -> str:
    invalid = {k: v for k, v in scanners.items() if not v.get("is_valid", True)}
    if not invalid:
        return "none"
    return max(invalid, key=lambda k: invalid[k].get("score", 0))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="LLM Guard golden dataset CI scanner")
    parser.add_argument("--url",     default="http://localhost:8000", help="URL de l'API LLM Guard")
    parser.add_argument("--dataset", default=".github/ci/golden-dataset.json", help="Chemin vers le golden dataset")
    parser.add_argument("--soft",    action="store_true", help="Ne pas échouer si l'API est indisponible")
    args = parser.parse_args()

    sys.exit(run_golden_dataset(args.url, args.dataset, args.soft))
