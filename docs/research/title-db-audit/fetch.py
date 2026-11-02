import json,sys,time,urllib.request,urllib.error,os
sys.stdout.reconfigure(encoding="utf-8")
OUT=sys.argv[1]
UA="SongRushBot/0.1 (+https://github.com/kimdoodoo12/SongRushApp)"
def post(url,body,gap):
    for attempt in range(6):
        req=urllib.request.Request(url,data=json.dumps(body).encode(),headers={"Content-Type":"application/json","Accept":"application/json","User-Agent":UA})
        try:
            with urllib.request.urlopen(req,timeout=120) as r: d=json.load(r)
            time.sleep(gap); 
            if d.get("errors"): print("errors",d["errors"][:1],flush=True)
            return d
        except urllib.error.HTTPError as e:
            ra=int(e.headers.get("Retry-After") or 30) if e.code==429 else 10*(attempt+1)
            print("http",e.code,"sleep",ra,flush=True); time.sleep(ra)
        except Exception as e:
            print("err",e,flush=True); time.sleep(10*(attempt+1))
    raise SystemExit("gave up")
atf=os.path.join(OUT,"at_anime.json")
if not os.path.exists(atf):
    q='query($p:Int){animePagination(first:100,page:$p){paginatorInfo{hasMorePages lastPage} data{id slug year format title{romaji} synonyms{text} series{nodes{id slug}} resources{nodes{site externalId}} animethemes{type}}}}'
    allA=[];p=1
    while True:
        t=time.time(); d=post("https://graphql.animethemes.moe/",{"query":q,"variables":{"p":p}},3.2)
        pg=d["data"]["animePagination"]; allA+=pg["data"]
        print("AT page",p,"/",pg["paginatorInfo"]["lastPage"],len(allA),f"{time.time()-t:.1f}s",flush=True)
        if not pg["paginatorInfo"]["hasMorePages"]: break
        p+=1
    json.dump(allA,open(atf,"w",encoding="utf-8"),ensure_ascii=False)
allA=json.load(open(atf,encoding="utf-8"))
ids=sorted({int(n["externalId"]) for a in allA for n in a["resources"]["nodes"] if n["site"]=="ANILIST" and n["externalId"]})
print("anilist ids",len(ids),flush=True)
alf=os.path.join(OUT,"al_media.json")
have=json.load(open(alf,encoding="utf-8")) if os.path.exists(alf) else {}
q='query($ids:[Int]){Page(perPage:50){media(id_in:$ids,type:ANIME){id title{romaji english native} synonyms format seasonYear popularity relations{edges{relationType(version:2) node{id type}}}}}}'
todo=[i for i in ids if str(i) not in have]
for k in range(0,len(todo),50):
    chunk=todo[k:k+50]
    d=post("https://graphql.anilist.co",{"query":q,"variables":{"ids":chunk}},2.2)
    for m in d["data"]["Page"]["media"]: have[str(m["id"])]=m
    if (k//50)%10==0:
        print("AL",k+len(chunk),"/",len(todo),flush=True); json.dump(have,open(alf,"w",encoding="utf-8"),ensure_ascii=False)
json.dump(have,open(alf,"w",encoding="utf-8"),ensure_ascii=False)
print("done",len(have))
