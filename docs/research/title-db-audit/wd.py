import json,sys,urllib.request,urllib.parse
sys.stdout.reconfigure(encoding="utf-8")
q='SELECT ?al ?lab WHERE { ?i wdt:P8729 ?al . { ?i rdfs:label ?lab } UNION { ?i skos:altLabel ?lab } FILTER(lang(?lab)="ko") }'
u="https://query.wikidata.org/sparql?"+urllib.parse.urlencode({"query":q,"format":"json"})
req=urllib.request.Request(u,headers={"User-Agent":"SongRushBot/0.1 (+https://github.com/kimdoodoo12/SongRushApp)","Accept":"application/sparql-results+json"})
with urllib.request.urlopen(req,timeout=120) as r: d=json.load(r)
out={}
for b in d["results"]["bindings"]: out.setdefault(b["al"]["value"],[]).append(b["lab"]["value"])
json.dump(out,open(sys.argv[1],"w",encoding="utf-8"),ensure_ascii=False); print("wikidata anilist ids with ko",len(out))
