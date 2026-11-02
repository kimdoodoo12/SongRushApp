import runpy,sys,json,re
sys.argv=["audit.py",sys.argv[1],sys.argv[2],sys.argv[3]]
import io,contextlib
buf=io.StringIO()
sys.stdout.reconfigure(encoding="utf-8"); g=runpy.run_path("audit.py")
acc=g["acc"]; HANG=g["HANG"]
allb=sum(len(json.dumps(sorted(d.keys()),ensure_ascii=False).encode()) for d in acc.values())
def keep(srcs): return any(not s.startswith("AOD") for s in srcs)
han=0
for d in acc.values():
    ks=[n for n,v in d.items() if any((not src.startswith("AOD")) or HANG.search(t) for _,src,t in v)]
    han+=len(json.dumps(sorted(ks),ensure_ascii=False).encode())
n_all=sum(len(d) for d in acc.values())
print("groups",len(acc),"strings all",n_all,"bytes all",allb,"| bytes with aod hangul-only",han)
