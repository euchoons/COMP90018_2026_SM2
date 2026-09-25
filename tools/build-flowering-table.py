#!/usr/bin/env python3
"""Regenerate app/src/main/assets/vicflora-flowering.tsv, the flowering data behind #16.

Scope: the 100 plant species most recorded in ALA within 8 km of Parkville, plus the guided-demo
species. Months come only from a VicFlora flowering statement that parses completely; any other
wording is kept as unparsed or missing, which the app treats as unknown (neutral). Stdlib only:

    python3 tools/build-flowering-table.py
"""
import datetime
import html
import json
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

OUT = "app/src/main/assets/vicflora-flowering.tsv"
ALA = "https://api.ala.org.au/occurrences/occurrences/search"
VICFLORA = "https://vicflora.rbg.vic.gov.au/graphql"
DEMO_SPECIES = [
    "Platanus × acerifolia", "Eucalyptus camaldulensis", "Acacia melanoxylon", "Acacia dealbata",
    "Trifolium repens", "Taraxacum officinale", "Callistemon citrinus", "Banksia integrifolia",
]
MONTHS = {m: i + 1 for i, m in enumerate("jan feb mar apr may jun jul aug sep oct nov dec".split())}
MONTHS["sept"] = 9
# Australian meteorological seasons, as used by the Bureau of Meteorology.
BOUNDS = {m: (n, n) for m, n in MONTHS.items()} | {
    "spring": (9, 11), "summer": (12, 2), "autumn": (3, 5), "winter": (6, 8),
}
ALL_YEAR = re.compile(r"\b(all year|throughout the year|most of (the )?year)\b")
COLUMNS = ["scientific_name", "status", "flowering_months", "source_text", "source_url",
           "profile_modified", "event", "region", "license", "retrieved"]


def request(url, body=None):
    headers = {"Accept": "application/json", "User-Agent": "FloraGuide-COMP90018/flowering-table"}
    if body is not None:
        headers["Content-Type"] = "application/json"
    for attempt in range(5):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, body, headers), timeout=60) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            if error.code not in (429, 500, 502, 503, 504) or attempt == 4:
                raise
            time.sleep(2 ** attempt * 2)


def vicflora(query):
    time.sleep(0.5)  # a public service: stay well below any rate limit
    return request(VICFLORA, json.dumps({"query": query}).encode())["data"]


def months_of(statement):
    """Months a flowering statement names, or None unless every word is understood."""
    text = statement.lower()
    if ALL_YEAR.search(text):
        return set(range(1, 13))
    text = re.sub(r"^flower(?:s|ing)(?: and fruits)?|\b(?:mainly|mostly|chiefly|usually)\b|\.", " ", text)
    months = set()
    for item in re.split(r"\s*(?:,|\band\b|\bor\b)\s*", text.strip()):
        ends = re.split(r"\s*(?:[–-]|\bto\b)\s*", item)
        if len(ends) > 2 or not all(end in BOUNDS for end in ends):
            return None
        month, last = BOUNDS[ends[0]][0], BOUNDS[ends[-1]][1]
        months.add(month)
        while month != last:
            month = month % 12 + 1
            months.add(month)
    return months


def flowering_statement(profile):
    """VicFlora ends a description paragraph with a short 'Flowers …' phenology sentence."""
    statements = []
    for paragraph in re.findall(r"<p[^>]*>(.*?)</p>", profile, re.S):
        text = re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", " ", paragraph))).strip()
        # Morphology like "Flowers 5-merous" also starts a sentence; only the short tail can be phenology.
        statements += [text[m.start():] for m in re.finditer(r"\bFlower(?:s|ing)\b", text)
                       if len(text) - m.start() <= 80]
    return statements[-1] if statements else None


def row(name, retrieved):
    docs = vicflora('{ search(input: {q: %s, rows: 5}) { docs { id scientificName taxonRank taxonomicStatus } } }'
                    % json.dumps(f'scientific_name:"{name}"'))["search"]["docs"]
    concept = next((d["id"] for d in docs if d["scientificName"] == name
                    and d["taxonRank"] == "species" and d["taxonomicStatus"] == "accepted"), None)
    status, months, statement, url, modified = "not_in_vicflora", None, "", "", ""
    if concept:
        url = f"https://vicflora.rbg.vic.gov.au/flora/taxon/{concept}"
        profile = vicflora('{ taxonConcept(id: "%s") { currentProfile { profile modified } } }'
                           % concept)["taxonConcept"]["currentProfile"] or {}
        modified = profile.get("modified") or ""
        statement = flowering_statement(profile.get("profile") or "") or ""
        months = months_of(statement) if statement else None
        status = "documented" if months else "unparsed" if statement else "no_statement"
    return [name, status, ",".join(map(str, sorted(months or []))), statement, url, modified,
            "flowering", "Victoria", "CC BY 4.0, Royal Botanic Gardens Victoria (VicFlora)", retrieved]


def main():
    params = {"q": "*:*", "fq": ["kingdom:Plantae", "taxonRank:species"], "lat": -37.7963, "lon": 144.9614,
              "radius": 8, "pageSize": 0, "facets": "species", "flimit": 100, "fsort": "count"}
    facet = request(ALA + "?" + urllib.parse.urlencode(params, doseq=True))["facetResults"][0]["fieldResult"]
    retrieved = datetime.date.today().isoformat()
    rows = [row(name, retrieved) for name in sorted(set([f["label"] for f in facet] + DEMO_SPECIES))]
    with open(OUT, "w", encoding="utf-8", newline="\n") as out:
        out.writelines("\t".join(r) + "\n" for r in [COLUMNS] + rows)
    for status in ("documented", "unparsed", "no_statement", "not_in_vicflora"):
        print(f"{status}: {sum(r[1] == status for r in rows)}", file=sys.stderr)


def check():
    """Wordings seen in VicFlora; runs offline before anything is overwritten."""
    assert months_of("Flowers summer.") == {12, 1, 2}
    assert months_of("Flowers mainly Oct.–Mar.") == {10, 11, 12, 1, 2, 3}
    assert months_of("Flowers Sept.–Dec") == {9, 10, 11, 12}
    assert months_of("Flowers mostly spring and summer.") == {9, 10, 11, 12, 1, 2}
    assert months_of("Flowering mostly spring–autumn") == {9, 10, 11, 12, 1, 2, 3, 4, 5}
    assert months_of("Flowers and fruits Sep.-Apr.") == {9, 10, 11, 12, 1, 2, 3, 4}
    assert months_of("Flowers most of year (rarely in winter).") == set(range(1, 13))
    assert months_of("Flowers Aug., Sep. (2 records).") is None
    assert months_of("Flowers 5-merous") is None
    assert flowering_statement("<p>Flowers 5-merous, long description of the petals and sepals and more. "
                               "Pod straight. Flowers Aug.–Oct.</p>") == "Flowers Aug.–Oct."


if __name__ == "__main__":
    check()
    main()
