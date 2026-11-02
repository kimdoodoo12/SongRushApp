import json,sys,re,unicodedata,collections,os
sys.stdout.reconfigure(encoding="utf-8")
D=sys.argv[1]; AOD=sys.argv[2]
AT=json.load(open(os.path.join(D,"at_anime.json"),encoding="utf-8"))
AL=json.load(open(os.path.join(D,"al_media.json"),encoding="utf-8"))
aod={}
for line in open(AOD,encoding="utf-8"):
    o=json.loads(line)
    for s in o.get("sources",[]):
        m=re.match(r"https://anilist\.co/anime/(\d+)$",s)
        if m: aod[m.group(1)]=o
import xml.etree.ElementTree as ET
WD=json.load(open(os.path.join(D,"wd_ko.json"),encoding="utf-8"))
ADB={}
for an in ET.parse(sys.argv[3]).getroot():
    ko=[t.text for t in an if t.get("{http://www.w3.org/XML/1998/namespace}lang")=="ko"]
    if ko: ADB[an.get("aid")]=ko
HANG=re.compile(r"[가-힣ㄱ-ㆎ]")
def norm(s):
    s=unicodedata.normalize("NFKC",s).lower()
    s=unicodedata.normalize("NFD",s); s="".join(c for c in s if not (0x300<=ord(c)<=0x36f)); s=unicodedata.normalize("NFC",s)
    return "".join(c for c in s if not (c.isspace() or unicodedata.category(c)[0] in "PSZ"))
def variants(s):
    out={s}
    t=re.sub(r"\s*\((?:19|20)\d\d(?:년)?(?:\s*애니메이션)?\)\s*$","",s); out.add(t)
    t2=re.sub(r"\s*\([^()]*애니메이션\)\s*$","",s); out.add(t2)
    return {x for x in out if x.strip()}
# catalog entries
cat=[]
for a in AT:
    if not any(t["type"] in ("OP","ED") for t in a["animethemes"]): continue
    al=[n["externalId"] for n in a["resources"]["nodes"] if n["site"]=="ANILIST" and n["externalId"]]
    cat.append({"id":a["id"],"slug":a["slug"],"year":a["year"],"title":a["title"]["romaji"],"syn":[x["text"] for x in a["synonyms"]],
                "series":{n["id"] for n in a["series"]["nodes"]},"al":str(al[0]) if al else None,"themes":len(a["animethemes"])})
print("AnimeThemes anime",len(AT),"| with OP/ED",len(cat),"| with AniList id",sum(1 for c in cat if c["al"]),"| AL fetched",sum(1 for c in cat if c["al"] in AL),"| in aod",sum(1 for c in cat if c["al"] in aod))
# strings per entry
for c in cat:
    S=[]  # (text, source, kind)
    S.append((c["title"],"AT.title","official"))
    for x in c["syn"]: S.append((x,"AT.syn","alias"))
    m=AL.get(c["al"]) if c["al"] else None
    if m:
        for k in ("romaji","english","native"):
            if m["title"].get(k): S.append((m["title"][k],"AL."+k,"official"))
        for x in m.get("synonyms") or []: S.append((x,"AL.syn","alias"))
    o=aod.get(c["al"]) if c["al"] else None
    if o:
        S.append((o["title"],"AOD.title","alias"))
        for x in o.get("synonyms",[]): S.append((x,"AOD.syn","alias"))
    for x in WD.get(c["al"] or "",[]): S.append((x,"WD.ko","alias"))
    if o:
        for src_ in o.get("sources",[]):
            mm=re.match(r"https://anidb\.net/anime/(\d+)$",src_)
            if mm:
                for x in ADB.get(mm.group(1),[]): S.append((x,"ADB.ko","alias"))
    c["strings"]=[(v,src,kind) for (t,src,kind) in S for v in variants(t)]
    c["pop"]=(m or {}).get("popularity") or 0
# grouping: union-find; AL components over SEQUEL/PREQUEL/SUMMARY
par={}
def f(x):
    par.setdefault(x,x)
    while par[x]!=x: par[x]=par[par[x]]; x=par[x]
    return x
def u(a,b): par[f(a)]=f(b)
for k,m in AL.items():
    f(k)
    for e in (m.get("relations") or {}).get("edges",[]):
        if e["relationType"] in ("SEQUEL","PREQUEL","SUMMARY") and e["node"]["type"]=="ANIME": u(k,str(e["node"]["id"]))
alcomp={c["id"]:(f(c["al"]) if c["al"] else None) for c in cat}
gp={}
def g(x):
    gp.setdefault(x,x)
    while gp[x]!=x: gp[x]=gp[gp[x]]; x=gp[x]
    return x
byser=collections.defaultdict(list)
for c in cat:
    g(c["id"])
    for s in c["series"]: byser[s].append(c)
for s,mem in byser.items():
    for i in range(len(mem)):
        for j in range(i+1,len(mem)):
            a,b=mem[i],mem[j]
            if alcomp[a["id"]] and alcomp[a["id"]]==alcomp[b["id"]]: gp[g(a["id"])]=g(b["id"])
groups=collections.defaultdict(list)
for c in cat: groups[g(c["id"])].append(c)
sizes=collections.Counter(len(v) for v in groups.values())
print("groups",len(groups),"size dist",sorted(sizes.items())[:12],"max",max(sizes))
big=sorted(groups.values(),key=len,reverse=True)[:6]
for m in big: print("  big group",len(m),":",", ".join(x["title"] for x in m[:8]),"...")
# accept sets
acc={}
for gid,mem in groups.items():
    d=collections.defaultdict(set)
    for c in mem:
        for v,src,kind in c["strings"]:
            n=norm(v)
            d[n].add((kind,src,v))
    acc[gid]=d
gpop={gid:max(c["pop"] for c in mem) for gid,mem in groups.items()}
gname={gid:sorted(mem,key=lambda c:(c["year"] or 9999))[0]["title"] for gid,mem in groups.items()}
# 1 empty norms
empt=[(gname[gid],[x[2] for x in d[""]]) for gid,d in acc.items() if "" in d]
print("\n[1] strings that normalize to empty:",len(empt),empt[:5])
# 2 collisions
owner=collections.defaultdict(set)
for gid,d in acc.items():
    for n in d:
        if n: owner[n].add(gid)
col={n:gs for n,gs in owner.items() if len(gs)>1}
def kinds(n,gid): return {k for k,_,_ in acc[gid][n]}
oo=[n for n,gs in col.items() if all("official" in kinds(n,gid) for gid in gs)]
ao=[n for n,gs in col.items() if n not in oo and any("official" in kinds(n,gid) for gid in gs)]
aa=[n for n,gs in col.items() if not any("official" in kinds(n,gid) for gid in gs)]
print(f"\n[2] collisions (same normalized string in 2+ groups): {len(col)}  official-vs-official {len(oo)}, alias-vs-official {len(ao)}, alias-vs-alias {len(aa)}")
def show(lst,k=12):
    lst=sorted(lst,key=lambda n:-max(gpop[x] for x in col[n]))
    for n in lst[:k]:
        print("   ",repr(n),"->"," | ".join(f"{gname[x]}[{'/'.join(sorted({s for _,s,_ in acc[x][n]}))}]" for x in sorted(col[n],key=lambda x:-gpop[x])[:4]))
print("  official-vs-official (top by popularity):"); show(oo)
print("  alias-vs-official:"); show(ao)
print("  alias-vs-alias:"); show(aa,8)
# 3 short
short=[(n,gname[gid],sorted({v for _,_,v in d[n]})) for gid,d in acc.items() for n in d if 0<len(n)<=2]
print(f"\n[3] normalized length<=2: {len(short)} strings; official among them:",sum(1 for n,gn,_ in short for gid in owner[n] if 'official' in kinds(n,gid)))
for x in sorted(short,key=lambda t:-max(gpop[g_] for g_ in owner[t[0]]))[:20]: print("   ",x)
# 4 generic
gen=sorted(((n,len(gs)) for n,gs in owner.items() if len(gs)>=4),key=lambda t:-t[1])
print("\n[4] strings in 4+ groups:",len(gen),gen[:25])
# 5 numeric
num=[(n,gname[gid]) for gid,d in acc.items() for n in d if n and n.isdigit()]
print("\n[5] digit-only strings:",len(num),num[:15])
# 6 chat words
chat=["ㅋㅋ","ㅋㅋㅋ","ㅎㅎ","ㅇㅇ","ㄴㄴ","네","응","아니","몰라","모름","패스","스킵","skip","pass","ok","gg","lol","정답","뭐지","헐","와","오","아","ㅠㅠ","yes","no","hi","안녕","ㄱㄱ","go","next","help","1","2","3","?","!!"]
hits=[(w,[gname[x] for x in owner.get(norm(w),())]) for w in chat if norm(w) in owner]
print("\n[6] common chat words that are accepted titles:",hits)
# 7 coverage
ranked=sorted(groups,key=lambda x:-gpop[x])
def cov(gids,pred): return sum(1 for x in gids if pred(x)),len(gids)
hasko=lambda x:any(HANG.search(v) for d in [acc[x]] for n in d for _,_,v in d[n])
haskoff=lambda x:any(HANG.search(v) and k=="official" for n in acc[x] for k,_,v in acc[x][n])
hasen=lambda x:any(src=="AL.english" for n in acc[x] for _,src,_ in acc[x][n])
for lab,gs in (("top100",ranked[:100]),("top500",ranked[:500]),("top1500 groups",ranked[:1500]),("all",ranked)):
    print(f"\n[7] {lab}: Korean any {cov(gs,hasko)}, English(AL) {cov(gs,hasen)}")
noko=[gname[x] for x in ranked[:300] if not hasko(x)]
print("  top300 groups without Korean:",len(noko),noko[:25])
# 8 noisy hangul aliases
noisy=[v for gid in acc for n in acc[gid] for _,_,v in acc[gid][n] if HANG.search(v) and (re.search(r"[A-Za-z]",v) and len(n)<=6 or "," in v or re.search(r"\d기",v))]
print("\n[8] hangul aliases with abbreviation/season markers:",len(set(noisy)),sorted(set(noisy))[:25])
json.dump({"collisions":{n:[gname[x] for x in gs] for n,gs in col.items()}},open(os.path.join(D,"audit_out.json"),"w",encoding="utf-8"),ensure_ascii=False,indent=0)
