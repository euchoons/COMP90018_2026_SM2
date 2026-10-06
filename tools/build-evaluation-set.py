#!/usr/bin/env python3
"""Collect #20 evaluation cases: plant observations near Parkville, their Pl@ntNet candidates and ALA counts.

Labels come from iNaturalist community identifications, not from Pl@ntNet. Everything the offline
training needs is cached in one JSON file, so FusionParameterTraining reruns without API calls. Each
observation costs one Pl@ntNet identification; the key is read from local.properties and never
written out. Stdlib only:

    python3 tools/build-evaluation-set.py                                   # the 300-observation pilot
    python3 tools/build-evaluation-set.py --per-stratum 65 --split train \\
        --exclude app/src/test/resources/evaluation/pilot.json --out app/src/test/resources/evaluation/train.json
    python3 tools/build-evaluation-set.py --per-stratum 1 --out /tmp/x.json  # smoke test
    python3 tools/build-evaluation-set.py --add-counts-without-inaturalist app/src/test/resources/evaluation/pilot.json
    python3 tools/build-evaluation-set.py --fill-missing-counts app/src/test/resources/evaluation/pilot.json
"""
import argparse
import concurrent.futures
import datetime
import hashlib
import json
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

OUT = "app/src/test/resources/evaluation/pilot.json"
PARKVILLE = (-37.7963, 144.9614)
SAMPLE_RADIUS_KM = 8
ALA_RADII_KM = (2, 8, 25)  # 8 km is the app's baseline
CANDIDATES = 5  # the app ranks and checks the first five Pl@ntNet results
MAX_PER_SPECIES = 2
INAT = "https://api.inaturalist.org/v2/observations"
PLANTNET = "https://my-api.plantnet.org/v2/identify/all"
ALA_NAMES = "https://api.ala.org.au/namematching/api/searchByClassification"
ALA_SEARCH = "https://api.ala.org.au/occurrences/occurrences/search"
GBIF = "https://api.gbif.org/v1/species"
WCVP = "f382f0ce-323a-4091-bb9f-add557f3a9a2"  # Kew's World Checklist of Vascular Plants on GBIF
COMMON = {"lat": PARKVILLE[0], "lng": PARKVILLE[1], "radius": SAMPLE_RADIUS_KM, "iconic_taxa": "Plantae",
          "rank": "species", "photo_license": "cc0,cc-by,cc-by-nc", "geoprivacy": "open", "acc_below": 2000,
          "photos": "true"}
STRATA = {
    "wild_flowering": {"quality_grade": "research", "captive": "false", "term_id": 12, "term_value_id": 13},
    "wild_other": {"quality_grade": "research", "captive": "false"},
    "cultivated": {"captive": "true", "identifications": "most_agree"},
}
FIELDS = ("id,observed_on,positional_accuracy,captive,quality_grade,location,community_taxon_id,"
          "num_identification_agreements,taxon.id,taxon.name,taxon.rank,user.id,photos.url,photos.license_code,"
          "photos.attribution,annotations.controlled_attribute_id,annotations.controlled_value_id")


def get(url, attempts=4):
    """JSON GET with retries on rate limits and server errors. Errors never include the URL."""
    for attempt in range(attempts):
        try:
            request = urllib.request.Request(url, headers={"Accept": "application/json",
                                                           "User-Agent": "FloraGuide-COMP90018/evaluation"})
            with urllib.request.urlopen(request, timeout=60) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            if error.code not in (429, 500, 502, 503, 504) or attempt == attempts - 1:
                raise
        except (urllib.error.URLError, TimeoutError):
            if attempt == attempts - 1:
                raise
        time.sleep(2 ** attempt * 2)


def flowering(obs):
    return any(a.get("controlled_attribute_id") == 12 and a.get("controlled_value_id") == 13
               for a in obs.get("annotations") or [])


def usable(obs, stratum):
    if not obs.get("photos") or not obs.get("location") or (obs.get("positional_accuracy") or 1e9) > 2000:
        return False
    if stratum == "wild_other" and flowering(obs):
        return False  # keeps the strata disjoint
    if stratum == "cultivated":  # casual grade, so require an agreed community identification
        return obs.get("community_taxon_id") == obs["taxon"]["id"] and obs.get("num_identification_agreements", 0) >= 2
    return True


def pool(stratum):
    params = {**COMMON, **STRATA[stratum], "per_page": 200, "order_by": "id", "order": "asc", "fields": FIELDS}
    found, last = [], 0
    while True:
        page = get(f"{INAT}?{urllib.parse.urlencode({**params, 'id_above': last})}")["results"]
        time.sleep(1)  # iNaturalist asks for about one request per second
        if not page:
            return [obs for obs in found if usable(obs, stratum)]
        found += page
        last = page[-1]["id"]


def digest(text):
    return hashlib.sha256(text.encode()).hexdigest()


def sample(pools, per_stratum, excluded_ids=frozenset()):
    """Hash order is a reproducible shuffle; caps stop common species or one plant from dominating.

    An excluded observation also excludes its observer's other records of that species, so the same
    plant cannot land in two datasets."""
    chosen, per_species = {}, {}
    observer_species = {(o["user"]["id"], o["taxon"]["id"]) for p in pools.values() for o in p if o["id"] in excluded_ids}
    for stratum, observations in pools.items():
        picks = []
        for obs in sorted(observations, key=lambda o: digest(f"pick:{o['id']}")):
            name, key = obs["taxon"]["name"], (obs["user"]["id"], obs["taxon"]["id"])
            if obs["id"] in excluded_ids or per_species.get(name, 0) >= MAX_PER_SPECIES or key in observer_species:
                continue
            per_species[name] = per_species.get(name, 0) + 1
            observer_species.add(key)
            picks.append(obs)
            if len(picks) == per_stratum:
                break
        chosen[stratum] = picks
    return chosen


def identify(key, obs):
    image = obs["photos"][0]["url"].replace("/square.", "/large.")
    query = urllib.parse.urlencode({"api-key": key, "images": image, "organs": "auto", "nb-results": 8, "lang": "en"})
    for attempt in range(3):
        try:
            with urllib.request.urlopen(urllib.request.Request(f"{PLANTNET}?{query}", headers={"Accept": "application/json"}),
                                        timeout=90) as response:
                body = json.load(response)
            break
        except urllib.error.HTTPError as error:
            if error.code == 404:  # Pl@ntNet recognised no plant
                return {"status": 404, "version": None, "organs": [], "results": []}
            if error.code == 429:
                return None  # daily quota used up; the observation is dropped, the rest are kept
            if error.code < 500 or attempt == 2:
                raise
        except (urllib.error.URLError, TimeoutError):
            if attempt == 2:
                raise
        time.sleep(5 * (attempt + 1))
    return {"status": 200, "version": body.get("version"),
            "organs": [{"organ": o["organ"], "score": o["score"]} for o in body.get("predictedOrgans", [])],
            "results": [{"name": r["species"]["scientificNameWithoutAuthor"], "score": r["score"]}
                        for r in body["results"]]}


def wcvp_label(name):
    """WCVP's accepted name for a label, so iNaturalist and Pl@ntNet names compare fairly."""
    query = urllib.parse.urlencode({"datasetKey": WCVP, "q": name, "rank": "SPECIES", "limit": 50})
    usages = [u for u in get(f"{GBIF}/search?{query}")["results"] if u.get("canonicalName") == name]
    if not usages or any(u["taxonomicStatus"] == "ACCEPTED" for u in usages):
        return name
    accepted = {get(f"{GBIF}/{u['acceptedKey']}")["canonicalName"] for u in usages
                if u["taxonomicStatus"] in ("SYNONYM", "HOMOTYPIC_SYNONYM", "HETEROTYPIC_SYNONYM") and u.get("acceptedKey")}
    return accepted.pop() if len(accepted) == 1 else name


def ala_name(name):
    match = get(f"{ALA_NAMES}?{urllib.parse.urlencode({'scientificName': name})}")
    return {k: match.get(k) for k in ("success", "scientificName", "rank", "matchType", "synonymType", "taxonConceptID")}


# The ranks AlaOccurrenceClient counts: a species or one plant below it, including unranked cultivars (#76).
COUNTABLE_RANKS = {"species", "subspecies", "variety", "form", "cultivar", "unranked"}


def countable(match):
    """A superset of what the app accepts; the evaluation applies the app's own parser to decide."""
    return (match.get("success") is True and (match.get("rank") or "").lower() in COUNTABLE_RANKS
            and match.get("matchType") in ("exactMatch", "canonicalMatch") and match.get("taxonConceptID"))


def ala_count(taxon_id, latitude, longitude, radius, observation_id, without_inaturalist=False):
    escaped = taxon_id.replace("\\", "\\\\").replace('"', '\\"')
    # The observation's own ALA copy would reward the right answer, so leave it out.
    own = f'-(dataResourceUid:dr1411 AND catalogNumber:"{observation_id}")'
    params = [("q", f'taxonConceptID:"{escaped}"'), ("lat", latitude), ("lon", longitude), ("radius", radius),
              ("fq", "-dataResourceUid:dr1411" if without_inaturalist else own), ("pageSize", 0), ("facet", "false")]
    return get(f"{ALA_SEARCH}?{urllib.parse.urlencode(params)}")["totalRecords"]


def add_counts_without_inaturalist(path, radius=8):
    """Robustness check: iNaturalist supplies both the photos and many ALA records, so recount without it.

    Only at the app's radius: ALA returned about 30 of these a minute, so all three radii would take over two hours."""
    with open(path, encoding="utf-8") as source:
        data = json.load(source)
    jobs = [((case["id"], name),
             (data["alaNames"][name]["taxonConceptID"], case["latitude"], case["longitude"], radius, case["id"], True))
            for case in data["cases"] for name in case["alaCounts"]]
    counts = run_parallel("ALA counts without iNaturalist", ala_count, jobs, 4)
    for case in data["cases"]:
        case["alaCountsWithoutINaturalist"] = {name: {str(radius): counts[(case["id"], name)]} for name in case["alaCounts"]}
    data["ala"]["withoutINaturalist"] = {"fq": "-dataResourceUid:dr1411", "radiusKm": radius,
                                         "retrieved": datetime.date.today().isoformat()}
    with open(path, "w", encoding="utf-8") as out:
        out.write(json.dumps(data, indent=1, ensure_ascii=False) + "\n")
    print(f"added counts without iNaturalist to {path}", file=sys.stderr)


def fill_missing_counts(path):
    """After the matching rule widened (#76), count the candidates the old rule skipped. Only those are queried."""
    with open(path, encoding="utf-8") as source:
        data = json.load(source)
    todo = [(case, r["name"]) for case in data["cases"] for r in case["plantnet"]["results"][:CANDIDATES]
            if countable(data["alaNames"][r["name"]]) and r["name"] not in case["alaCounts"]]
    taxon = lambda name: data["alaNames"][name]["taxonConceptID"]
    jobs = [((case["id"], name, radius), (taxon(name), case["latitude"], case["longitude"], radius, case["id"]))
            for case, name in todo for radius in ALA_RADII_KM]
    without = data["ala"].get("withoutINaturalist")
    if without:
        jobs += [((case["id"], name, "without"), (taxon(name), case["latitude"], case["longitude"], without["radiusKm"],
                                                   case["id"], True)) for case, name in todo]
    counts = run_parallel("missing ALA counts", ala_count, jobs, 4)
    for case, name in todo:
        case["alaCounts"][name] = {str(radius): counts[(case["id"], name, radius)] for radius in ALA_RADII_KM}
        if without:
            case["alaCountsWithoutINaturalist"][name] = {str(without["radiusKm"]): counts[(case["id"], name, "without")]}
    data["ala"]["filledAfterMatchingChange"] = {"issue": "#76", "pairs": len(todo),
                                                "retrieved": datetime.date.today().isoformat()}
    with open(path, "w", encoding="utf-8") as out:
        out.write(json.dumps(data, indent=1, ensure_ascii=False) + "\n")
    print(f"filled {len(todo)} candidate counts in {path}", file=sys.stderr)


def run_parallel(label, function, items, workers):
    results = {}
    with concurrent.futures.ThreadPoolExecutor(workers) as executor:
        futures = {executor.submit(function, *item[1]): item[0] for item in items}
        for done, future in enumerate(concurrent.futures.as_completed(futures), 1):
            results[futures[future]] = future.result()
            if done % 50 == 0 or done == len(futures):
                print(f"  {label}: {done}/{len(futures)}", file=sys.stderr)
    return results


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--per-stratum", type=int, default=100)
    parser.add_argument("--out", default=OUT)
    parser.add_argument("--split", help="label every case with this split instead of the hash-based dev/holdout")
    parser.add_argument("--exclude", action="append", default=[], help="an earlier dataset whose observations to avoid")
    parser.add_argument("--fill-missing-counts", action="append", default=[], metavar="DATASET",
                        help="query ALA counts only for candidates an earlier, narrower matching rule skipped")
    parser.add_argument("--add-counts-without-inaturalist", action="append", default=[], metavar="DATASET",
                        help="recount an existing dataset's ALA records without iNaturalist, instead of collecting")
    args = parser.parse_args()
    if args.fill_missing_counts:
        for path in args.fill_missing_counts:
            fill_missing_counts(path)
        return
    if args.add_counts_without_inaturalist:
        for path in args.add_counts_without_inaturalist:
            add_counts_without_inaturalist(path)
        return
    key = re.search(r"^plantnet\.api\.key=(.+)$", open("local.properties", encoding="utf-8").read(), re.M).group(1).strip()
    excluded_ids = frozenset(c["id"] for path in args.exclude for c in json.load(open(path, encoding="utf-8"))["cases"])

    pools = {stratum: pool(stratum) for stratum in STRATA}
    print("pools:", {s: len(p) for s, p in pools.items()}, file=sys.stderr)
    chosen = sample(pools, args.per_stratum, excluded_ids)
    observations = [(stratum, obs) for stratum, picks in chosen.items() for obs in picks]

    identifications = run_parallel("Pl@ntNet", identify, [(obs["id"], (key, obs)) for _, obs in observations], 3)
    dropped = [obs["id"] for _, obs in observations if identifications[obs["id"]] is None]
    if dropped:
        print(f"Pl@ntNet quota reached: {len(dropped)} observations dropped", file=sys.stderr)
    observations = [(stratum, obs) for stratum, obs in observations if identifications[obs["id"]] is not None]
    labels = run_parallel("WCVP labels", wcvp_label,
                          [(n, (n,)) for n in sorted({obs["taxon"]["name"] for _, obs in observations})], 4)
    names = sorted({r["name"] for i in identifications.values() for r in i["results"][:CANDIDATES]})
    matches = run_parallel("ALA names", ala_name, [(n, (n,)) for n in names], 4)

    cases, count_jobs = [], []
    for stratum, obs in observations:
        latitude, longitude = (round(float(v), 3) for v in obs["location"].split(","))  # as the app coarsens
        plantnet = identifications[obs["id"]]
        for result in plantnet["results"][:CANDIDATES]:
            match = matches[result["name"]]
            if countable(match):
                for radius in ALA_RADII_KM:
                    count_jobs.append(((obs["id"], result["name"], radius),
                                       (match["taxonConceptID"], latitude, longitude, radius, obs["id"])))
        photo = obs["photos"][0]
        cases.append({
            "id": obs["id"], "stratum": stratum,
            "split": args.split or ("holdout" if int(digest(f"split:{obs['id']}"), 16) % 2 else "dev"),
            "label": obs["taxon"]["name"], "labelWcvp": labels[obs["taxon"]["name"]],
            "qualityGrade": obs["quality_grade"], "captive": obs["captive"], "flowering": flowering(obs),
            "observedOn": obs["observed_on"], "latitude": latitude, "longitude": longitude,
            "accuracyM": obs["positional_accuracy"],
            "photo": {"url": photo["url"].replace("/square.", "/large."), "license": photo["license_code"],
                      "attribution": photo["attribution"]},
            "plantnet": plantnet,
        })
    counts = run_parallel("ALA counts", ala_count, count_jobs, 4)
    for case in cases:
        case["alaCounts"] = {}
        for (obs_id, name, radius), total in counts.items():
            if obs_id == case["id"]:
                case["alaCounts"].setdefault(name, {})[str(radius)] = total

    data = {
        "retrieved": datetime.date.today().isoformat(),
        "sampling": {"source": "iNaturalist API v2", "center": PARKVILLE, "radiusKm": SAMPLE_RADIUS_KM,
                     "filters": COMMON, "strata": STRATA, "perStratum": args.per_stratum,
                     "maxPerSpecies": MAX_PER_SPECIES, "oneObservationPerObserverAndSpecies": True,
                     "poolSizes": {s: len(p) for s, p in pools.items()},
                     "order": "sha256('pick:' + id)",
                     "split": args.split or "sha256('split:' + id) odd = holdout",
                     "excludes": {"datasets": args.exclude, "observations": len(excluded_ids),
                                  "rule": "their ids and their observers' other records of the same species"},
                     "droppedForQuota": len(dropped)},
        "plantnet": {"endpoint": PLANTNET, "organs": "auto", "nbResults": 8, "imageSize": "large"},
        "ala": {"radiiKm": list(ALA_RADII_KM), "baselineRadiusKm": 8, "coordinateDecimals": 3,
                "excludesOwnRecord": "dataResourceUid:dr1411 AND catalogNumber:<observation id>"},
        "alaNames": matches,
        "cases": cases,
    }
    text = json.dumps(data, indent=1, ensure_ascii=False)
    assert key not in text, "the Pl@ntNet key must never be written"
    with open(args.out, "w", encoding="utf-8") as out:
        out.write(text + "\n")
    print(f"wrote {len(cases)} cases to {args.out}", file=sys.stderr)


if __name__ == "__main__":
    main()
