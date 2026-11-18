import argparse,pathlib,json,sqlite3,hashlib,re,ast
parser=argparse.ArgumentParser()
parser.add_argument("snapshot",type=pathlib.Path)
args=parser.parse_args();r=args.snapshot
evidence=pathlib.Path(__file__).parent/"47dece72"
schemas={v:json.loads((r/f"android/nativeapp/schemas/com.jeevavibeapp.spendwise.data.SpendDatabase/{v}.json").read_text())["database"] for v in (3,4)}
def database(v):
 c=sqlite3.connect(":memory:")
 for e in schemas[v]["entities"]:
  c.execute(e["createSql"].replace("$"+"{TABLE_NAME}",e["tableName"]))
  for idx in e.get("indices",[]):c.execute(idx["createSql"].replace("$"+"{TABLE_NAME}",e["tableName"]))
 return c
def insert(c,id,key):
 return c.execute("INSERT OR IGNORE INTO transactions(id,amount,type,occurredAt,source,status,categoryPrompted,dedupKey,isDeleted,createdAt) VALUES(?,?,?,?,?,?,?,?,?,?)",(id,500.,"expense",0,"sms","confirmed",0,hashlib.sha256(key.encode()).hexdigest(),0,0)).rowcount
raw=(evidence/"evidence/AuditHarness-47dece72.txt").read_text(encoding="utf8")
keys={l.split("\t")[0]:l.split(";key=",1)[1] for l in raw.splitlines() if ";key=" in l}
trials={}
for name,a,b in [("identical","dedup_no_ref_noon","dedup_no_ref_noon"),("distinct_purchases","dedup_no_ref_noon","dedup_no_ref_evening"),("debit_credit","dedup_no_ref_noon","dedup_credit")]:
 c=database(4);ins=[insert(c,"1",keys[a]),insert(c,"2",keys[b])]
 trials[name]={"keys":[keys[a],keys[b]],"inserted":ins,"rows":c.execute("SELECT count(*) FROM transactions").fetchone()[0]}
def schema(c):
 d={}
 for (table,) in c.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"):
  d[table]={"columns":c.execute("PRAGMA table_info("+table+")").fetchall(),"indexes":{}}
  for idx in c.execute("PRAGMA index_list("+table+")").fetchall():d[table]["indexes"][idx[1]]=(idx[2],c.execute("PRAGMA index_info("+idx[1]+")").fetchall())
 return d
c=database(3)
sql=(r/"android/nativeapp/src/main/java/com/jeevavibeapp/spendwise/data/Misses.kt").read_text(encoding="utf8")
for stmt in re.findall(r'db\.execSQL\(((?:"(?:\\.|[^"\\])*"|\s|\+)+)\)',sql,re.S):
 c.execute("".join(ast.literal_eval(x) for x in re.findall(r'"(?:\\.|[^"\\])*"',stmt)))
a=[l.split("\t") for l in (evidence/"extra-evidence/SolLegacyKeyProbeKt-6bd77fb5.txt").read_text(encoding="utf8").splitlines()]
b=[l.split("\t") for l in (evidence/"extra-evidence/SolLegacyKeyProbeKt-47dece72.txt").read_text(encoding="utf8").splitlines()]
legacy=[]
for x,y in zip(a,b):
 db=database(4);ins=[insert(db,"old",x[2]),insert(db,"new",y[2])]
 legacy.append({"same_key":x[2]==y[2],"inserted":ins})
print(json.dumps({"trials":trials,"migration_3_to_4_matches_schema":schema(c)==schema(database(4)),"legacy_cases":legacy},indent=2))
