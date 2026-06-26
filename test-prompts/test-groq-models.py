#!/usr/bin/env python3
"""
Test comparatif des modèles Groq pour le projet multi-agent SaaS.
Usage: python3 test-prompts/test-groq-models.py
La clé Groq est lue depuis test-prompts/.env (GROQ_KEY=gsk_...)
"""
import urllib.request, json, time, re, os

# ── Chargement .env ──────────────────────────────────────
def load_env(path):
    env = {}
    try:
        with open(path) as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    k, v = line.split("=", 1)
                    env[k.strip()] = v.strip()
    except FileNotFoundError:
        print(f"❌ Fichier .env introuvable : {path}")
        raise
    return env

_dir = os.path.dirname(os.path.abspath(__file__))
env  = load_env(os.path.join(_dir, ".env"))

GROQ_KEY = env.get("GROQ_KEY", "")
if not GROQ_KEY:
    raise RuntimeError("GROQ_KEY absent du fichier .env")

URL = "https://api.groq.com/openai/v1/chat/completions"

# ── Prompts ──────────────────────────────────────────────
SYS_CLASSIFIER = (
    "Tu es un classificateur d'intention pour une app de gestion ESN. "
    "Retourne UNIQUEMENT un JSON: {\"intent\":\"...\",\"confidence\":0.0}\n"
    "Intents: create_expense, generate_expense_report, delete_expense, smalltalk, unknown\n"
    "REGLE: create_expense pour tout achat, depense, frais, repas, km, transport.\n"
    "Ignore les balises [absences:...]."
)

SYS_EXTRACTOR = (
    "Tu es un extracteur de notes de frais. Retourne UNIQUEMENT un JSON:\n"
    "{\"amount\":0.0,\"currency\":\"EUR\",\"type\":\"...\",\"date\":\"YYYY-MM-DD\","
    "\"description\":\"...\",\"paymentMode\":\"Personnel ou Business\",\"company\":null}\n"
    "Types: restaurant, materiel_informatique, transport, frais_km, hotel, autre\n"
    "paymentMode=Business si texte contient: carte business, compte entreprise, business card, compte business."
)

# ── Jeux de test ─────────────────────────────────────────
CLASSIF_TESTS = [
    ("Achat ecran Dell 27 pouces 389 euros carte business",       "create_expense",          "ecran FR"),
    ("Repas client chez Paul Paris 45 euros hier soir",           "create_expense",          "repas FR"),
    ("Genere le rapport de frais du mois de mai",                 "generate_expense_report", "rapport FR"),
    ("Supprime la note de frais du 12 juin",                      "delete_expense",          "suppression FR"),
    ("Bonjour comment ca va ?",                                   "smalltalk",               "smalltalk FR"),
    ("45km trajet client ce mois [absences: 2026-06-09]",         "create_expense",          "km+absences FR"),
    ("Bought a Dell screen 389 euros business card",              "create_expense",          "screen EN"),
    ("Compre monitor Dell 389 euros tarjeta empresa",             "create_expense",          "pantalla ES"),
]

EXTRACT_TESTS = [
    "Achat ecran Dell 27 pouces le 15 mai 2026 pour le teletravail, 389 euros carte business, societe IA-INSIGHT",
    "Repas client restaurant Paul Paris 18eme, 45,50 euros, hier, paye personnellement",
    "Hotel Mercure Lyon 2 nuits du 10 au 12 juin 2026, 187 euros carte business societe IA-INSIGHT",
]

MODELS = [
    ("llama-3.1-8b-instant",                      "8B Instant   "),
    ("meta-llama/llama-4-scout-17b-16e-instruct", "Llama-4 Scout"),
    ("qwen/qwen3-32b",                            "Qwen3 32B    "),
]

# ── Helpers ───────────────────────────────────────────────
def call(model, system, user):
    body = {
        "model": model,
        "temperature": 0,
        "messages": [
            {"role": "system", "content": system},
            {"role": "user",   "content": user},
        ]
    }
    payload = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(URL, data=payload, headers={
        "Authorization": f"Bearer {GROQ_KEY}",
        "Content-Type": "application/json",
        "User-Agent": "curl/8.4.0"
    }, method="POST")
    with urllib.request.urlopen(req, timeout=25) as r:
        d = json.loads(r.read())
        text   = d["choices"][0]["message"]["content"].strip()
        tokens = d["usage"]["total_tokens"]
        ms     = int(d["usage"]["total_time"] * 1000)
        return text, tokens, ms

def parse_json(text):
    match = re.search(r'\{[^{}]*\}', text, re.DOTALL)
    if match:
        return json.loads(match.group())
    return {}

def print_bar(score, total=8):
    return "█" * score + "░" * (total - score)

# ── Benchmark ─────────────────────────────────────────────
print("\n" + "═"*62)
print("  BENCHMARK GROQ — Classification & Extraction de frais")
print("═"*62)

all_scores = {}

print("\n📋 TEST 1 — CLASSIFICATION D'INTENT (FR / EN / ES)\n")
for model_id, model_name in MODELS:
    print(f"── {model_name} ──────────────────────────────")
    score = 0; total_tok = 0; total_ms = 0
    for msg, expected, label in CLASSIF_TESTS:
        try:
            text, tok, ms = call(model_id, SYS_CLASSIFIER, msg)
            r = parse_json(text)
            intent = r.get("intent", "?")
            conf   = round(r.get("confidence", 0), 2)
            ok = "✓" if intent == expected else "✗"
            if ok == "✓": score += 1
            total_tok += tok; total_ms += ms
            print(f"  {ok} [{label:<22}] → {intent:<28} conf={conf}  ({ms}ms)")
        except Exception as e:
            print(f"  ✗ [{label:<22}] → ERR: {e}")
        time.sleep(0.3)
    avg = total_ms // max(len(CLASSIF_TESTS), 1)
    print(f"  Score: {score}/8  {print_bar(score)}  moy={avg}ms  tokens={total_tok}\n")
    all_scores[model_name] = {"score": score, "avg_ms": avg}

print("\n📄 TEST 2 — EXTRACTION JSON NOTE DE FRAIS\n")
for model_id, model_name in MODELS:
    print(f"── {model_name} ──────────────────────────────")
    for i, msg in enumerate(EXTRACT_TESTS):
        try:
            text, tok, ms = call(model_id, SYS_EXTRACTOR, msg)
            r = parse_json(text)
            amount = r.get("amount", "?")
            pm     = r.get("paymentMode", "?")
            typ    = r.get("type", "?")
            date   = r.get("date", "?")
            desc   = str(r.get("description", "?"))[:45]
            ok_amount = "✓" if amount != "?" else "✗"
            ok_mode   = "✓" if pm in ["Personnel", "Business"] else "✗"
            print(f"  Cas {i+1}: {ok_amount}amount={amount} | {ok_mode}mode={pm} | type={typ} | date={date}  ({ms}ms)")
            print(f"         desc: {desc}")
        except Exception as e:
            print(f"  Cas {i+1}: ERR — {e}")
        time.sleep(0.4)
    print()

print("\n" + "═"*62)
print("  RÉSUMÉ FINAL")
print("═"*62)
print(f"  {'Modèle':<20} {'Score classif':>14}  {'Vitesse moy':>12}  Recommandation")
print("  " + "─"*56)
for mname, r in all_scores.items():
    s = r["score"]; ms = r["avg_ms"]
    if s == 8 and ms < 500:   rec = "⭐ Excellent"
    elif s >= 7 and ms < 800: rec = "✓ Bon"
    elif s >= 6:               rec = "~ Acceptable"
    else:                      rec = "✗ Insuffisant"
    print(f"  {mname:<20} {s}/8  {print_bar(s):<12}  {ms:>5}ms  {rec}")

print()
