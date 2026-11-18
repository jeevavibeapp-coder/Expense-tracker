#!/usr/bin/env python3
"""Local, review-required pseudonymisation of bank SMS. Python standard library only."""
from __future__ import annotations
import argparse, hashlib, hmac, json, os, re, secrets, sys, tempfile, unicodedata
import xml.etree.ElementTree as ET
from pathlib import Path
from datetime import datetime, timezone

VERSION = 1
MAX_BYTES = 64 * 1024 * 1024
MERCHANTS = {
 "AMAZON","AMAZON PAY","FLIPKART","SWIGGY","SWIGGY INSTAMART","ZOMATO","BLINKIT",
 "ZEPTO","BIGBASKET","DMART","RELIANCE FRESH","NETFLIX","SPOTIFY","UBER","OLA",
 "IRCTC","AIRTEL","JIO","BSNL","BESCOM","TATA POWER","APOLLO","PHARMEASY","MYNTRA",
 "GOOGLE","GOOGLE PAY","PHONEPE","PAYTM","ACT FIBERNET","YOUTUBE","BOOKMYSHOW",
}
BANK_WORDS = {"HDFC","ICICI","SBI","AXIS","KOTAK","IDFC","IDFCFIRST","PNB","BOB",
 "CANARA","UNION","YES","HSBC","CITI","INDUSIND","FEDERAL","BANK","PAYMENTS"}
PSPS = {"ybl","ibl","axl","paytm","upi","apl","okaxis","okhdfcbank","okicici",
 "oksbi","icici","sbi","hdfcbank","axisbank","kotak","indus","pockets","airtel"}
BODY_KEYS = {"body","text","content","messagetext","smsbody"}
SENDER_KEYS = {"address","sender","from","originatingaddress","sourceaddress","phonenumber"}
DATE_KEYS = {"date","datesent","datetime","timestamp","time","receivedat","sentat","readabledate"}
def canon(value):
 return " ".join(unicodedata.normalize("NFKC",value).upper().split())
def digits(value):
 return "".join(str(unicodedata.decimal(c)) for c in value if c.isdecimal())
def keyname(value):
 return re.sub(r"[^a-z0-9]","",value.rsplit("}",1)[-1].lower())
def atomic(path, text):
 path.parent.mkdir(parents=True,exist_ok=True)
 fd,tmp=tempfile.mkstemp(prefix=".redact-",dir=path.parent)
 try:
  with os.fdopen(fd,"w",encoding="utf-8",newline="\n") as f:
   f.write(text);f.flush();os.fsync(f.fileno())
  os.replace(tmp,path)
 finally:
  if os.path.exists(tmp):os.unlink(tmp)

class Mapper:
 """State stores a secret and keyed digests, not the original values."""
 def __init__(self,path=None,seed=None):
  self.path=Path(path) if path else None
  if self.path and self.path.exists():
   self.state=json.loads(self.path.read_text(encoding="utf-8"))
   if self.state.get("version")!=VERSION:raise ValueError("Unsupported redaction-state version")
  else:self.state={"version":VERSION,"secret":seed or secrets.token_hex(32),"maps":{}}
  self.secret=bytes.fromhex(self.state["secret"])
  self.maps=self.state["maps"]
 def digest(self,value):
  return hmac.new(self.secret,value.encode("utf-8"),hashlib.sha256).hexdigest()
 def mapped(self,kind,value,shape,make):
  k=kind+":"+self.digest(value)
  if k not in self.maps:
   used={v["value"] for v in self.maps.values() if v["shape"]==shape}
   for attempt in range(10000):
    out=make(self.digest(kind+":"+value+":"+str(attempt)))
    if out!=value and out not in used:break
   else:raise ValueError("Pseudonym space exhausted; cannot avoid a collision")
   self.maps[k]={"value":out,"shape":shape}
  return self.maps[k]["value"]
 def number(self,value):
  raw=digits(value)
  if not raw:return value
  def make(d):
   stream=(d*((len(raw)//len(d))+1))[:len(raw)]
   out="".join(str(int(c,16)%10) for c in stream)
   first=str(6+int(d[0],16)%4) if len(raw)>=10 else str(1+int(d[0],16)%9)
   return first+out[1:]
  fake=self.mapped("number",raw,"D"*len(raw),make)
  it=iter(fake);out=[]
  for c in value:
   if c.isdecimal():
    n=int(next(it));out.append(chr(ord(c)-unicodedata.decimal(c)+n))
   else:out.append(c)
  return "".join(out)
 def shape(self,value):
  if all(not c.isalpha() for c in value):return self.number(value)
  normalized=canon(value)
  shape="".join("A" if c.isupper() else "a" if c.islower() else "D" if c.isdecimal() else c for c in value)
  def make(d):
   out=[];j=0
   for c in value:
    n=int(d[(j*2)%len(d):(j*2)%len(d)+2] or d[:2],16)
    if c.isalpha():out.append(chr(65+n%26));j+=1
    elif c.isdecimal():out.append(str(n%10));j+=1
    else:out.append(c)
   return "".join(out)
  prototype=self.mapped("shape",normalized+"|"+shape.upper(),shape.upper(),make)
  return "".join(c.lower() if original.islower() else c for original,c in zip(value,prototype))
 def person(self,value):
  val=canon(value)
  k="person:"+self.digest(val)
  if k not in self.maps:
   n=1+sum(k.startswith("person:") for k in self.maps)
   self.maps[k]={"value":"PERSON_"+str(n),"shape":"PERSON"}
  return self.maps[k]["value"]
 def save(self):
  if self.path:atomic(self.path,json.dumps(self.state,ensure_ascii=False,indent=2)+"\n")

class Redactor:
 def __init__(self,mapper,merchants=()):
  self.mapper=mapper;self.merchants=MERCHANTS|{canon(x) for x in merchants if x.strip()}
  self.review=[];self.counts={}
 def note(self,index,token,reason,action="masked"):
  self.review.append({"message":index,"token":token,"reason":reason,"action":action})
 def redact(self,body,index=1):
  if "\ue000" in body or "\ue001" in body:raise ValueError("Input contains reserved redaction marker")
  vault=[]
  def hold(value):
   n=len(vault);letters=""
   while True:
    letters=chr(65+n%26)+letters;n=n//26-1
    if n<0:break
   vault.append(value);return "\ue000"+letters+"\ue001"
  def change(kind,value):
   self.counts[kind]=self.counts.get(kind,0)+1
   return hold(value)
  def number(m,kind="identifier"):
   return change(kind,self.mapper.number(m.group()))
  # Links can contain names, account IDs, tokens or phone numbers in their paths.
  body=re.sub(r"(?:https?://|www\.)[^\s<>]+|\b(?:[A-Za-z0-9-]+\.)+[A-Za-z]{2,}/[^\s<>]+",lambda m:(self.note(index,m.group(),"URL may contain identifying query/path data") or change("url","https://redacted.invalid/"+self.mapper.digest(m.group())[:12])),body,flags=re.I)
  # Axis-style paths carry both a reference and a P2P/P2M distinction.
  def path(m):
   label,reference,party=m.group(1),m.group(2),m.group(3)
   ref=change("reference",self.mapper.shape(reference))
   if label.upper()=="P2P" and not re.fullmatch(r"[\w.+-]+@[A-Za-z][A-Za-z0-9.-]+",party.strip()):
    party=change("person",self.mapper.person(party));self.note(index,m.group(3),"P2P path counterparty name")
   # Leave P2M text visible to later identifier masking, even if it includes a VPA.
   return "UPI/"+label+"/"+ref+"/"+party
  body=re.sub(r"\bUPI/(P2[MP])/([A-Za-z0-9]{6,})/([^/\r\n]+?)(?=\s+(?:on|at|via|using|ref(?:erence)?|utr|rrn|avl|available|balance)\b|[/;\r\n]|$)",path,body,flags=re.I)
  def generic_path(m):
   mode=m.group(1) or ""
   reference=change("reference",self.mapper.shape(m.group(2)))
   party=m.group(3);norm=canon(party)
   if re.fullmatch(r"[\w.+-]+@[A-Za-z][A-Za-z0-9.-]+",party.strip()):pass
   elif norm in self.merchants:party=hold(party)
   else:
    self.note(index,party,"UPI path counterparty has no explicit P2M marker; confirm person/merchant")
    party=change("person",self.mapper.person(party))
   return "UPI/"+(mode+"/" if mode else "")+reference+"/"+party
  body=re.sub(r"\bUPI/(?:(CR|DR)/)?([A-Za-z0-9]{6,})/([^/\r\n]+?)(?=\s+(?:on|at|via|using|ref(?:erence)?|utr|rrn|avl|available|balance)\b|[/;\r\n]|$)",generic_path,body,flags=re.I)
  # Account/card labels, followed by masked or unmasked numeric identifiers.
  account=re.compile(r"(?P<label>\b(?:a/c|acct|account|card|a/c\s+no)\b(?:\s+(?:no\.?|number|ending(?:\s+in)?))?\s*[:#-]?\s*)(?P<val>[xX*#•]*\d[\dxX*#• -]{1,28}\d)",re.I)
  body=account.sub(lambda m:m.group("label")+change("account_card",self.mapper.number(m.group("val"))),body)
  body=re.sub(r"(?<!\w)[xX*#•]{1,}\d{2,}(?!\w)",lambda m:number(m,"account_card"),body)
  # References, including Refno, RRN and UTR. Keep the keyword itself.
  ref=re.compile(r"(?P<label>\b(?:upi\s*ref(?:erence)?|ref(?:erence)?(?:\s*(?:no\.?|number|id))?|refno|utr|rrn|txn(?:\s*id)?|transaction\s*id)\s*[:.#-]?\s*)(?P<val>[A-Za-z0-9][A-Za-z0-9/_-]{5,})",re.I)
  body=ref.sub(lambda m:m.group("label")+change("reference",self.mapper.shape(m.group("val"))),body)
  # Balances are sensitive, transaction amounts are intentionally retained.
  bal=re.compile(r"(?P<label>\b(?:(?:avl|avail(?:able)?|closing|current|ledger|total)\.?\s*)?bal(?:ance)?\.?\s*(?:is|:|-)?\s*(?:[+-]\s*)?(?:(?:rs\.?|inr|₹)\s*)?)(?P<val>[+-]?\d[\d,]*(?:\.\d+)?)",re.I)
  body=bal.sub(lambda m:m.group("label")+change("balance",self.mapper.number(m.group("val"))),body)
  # UPI/email-like identifiers; PSP suffix retained when recognised.
  def handle(m):
   local,domain=m.group(1),m.group(2)
   trail="." if domain.endswith(".") else "";domain=domain.rstrip(".")
   if domain.lower() not in PSPS:
    self.note(index,m.group(),"Unrecognised UPI/email domain; both sides masked")
    domain=self.mapper.shape(domain)
   return change("upi_handle",self.mapper.shape(local)+"@"+domain)+trail
  body=re.sub(r"(?<![\w.+-])([\w.+-]{1,})@([A-Za-z][A-Za-z0-9.-]{1,})(?!\w)",handle,body)
  # Dates/times and transaction currency amounts must survive general ID checks.
  protected=[
   r"\b\d{4}-\d{2}-\d{2}(?:[T ]\d{1,2}:\d{2}(?::\d{2})?(?:Z|[+-]\d{2}:\d{2})?)?",
   r"\b\d{1,2}[-/](?:\d{1,2}|[A-Za-z]{3,9})[-/]\d{2,4}\b",
   r"\b\d{1,2}[A-Za-z]{3}\d{2,4}\b",
   r"\b\d{1,2}\s+[A-Za-z]{3,9}\s+\d{2,4}\b",
   r"\b\d{1,2}:\d{2}(?::\d{2})?(?:\s*[AP]M)?\b",
   r"(?:\b(?:rs\.?|inr)|₹)\s*[+-]?\d[\d,]*(?:\.\d+)?",
   r"\b(?:debited|credited)\s+(?:by|with|for|of)\s+\d[\d,]*(?:\.\d+)?",
  ]
  for pattern in protected:body=re.sub(pattern,lambda m:hold(m.group()),body,flags=re.I)
  # Phone numbers, allowing +91 and usual spacing.
  def phone(m):
   token=m.group();ds=digits(token)
   if ds.startswith("91") and len(ds)==12:
    start=next(i for i,c in enumerate(token) if c.isdecimal())
    seen=0;cut=start
    for j,c in enumerate(token[start:],start):
     if c.isdecimal():seen+=1
     if seen==2:cut=j+1;break
    return change("phone",token[:cut]+self.mapper.number(token[cut:]))
   return change("phone",self.mapper.number(token))
  body=re.sub(r"(?<!\w)(?:\+?91[\s-]?)?[6-9](?:[\s-]?\d){9}(?!\d)",phone,body)
  # Card digit groups without a label.
  body=re.sub(r"(?<!\d)\d{4}(?:[ -]\d{4}){3}(?!\d)",lambda m:number(m,"account_card"),body)
  # Counterparty names: explicit P2P is clear; unknown names are masked pending review.
  boundary=r"(?=\s+(?:on|at|via|using|upi|ref\w*|utr|rrn|from|to|has|was|is|avl|available|balance|a/c|acct|account|credited|debited)\b|\s*\ue000|[;,()\r\n]|$)"
  party=re.compile(r"(?P<label>\b(?:to|from|by|at|(?:account|card)\s*holder(?:\s+name)?|beneficiary(?:\s+name)?|remitter(?:\s+name)?|payee(?:\s+name)?|payer(?:\s+name)?)\s*[:=-]?\s+)(?P<name>[^\W\d_][\w .&'-]{0,70}?)"+boundary,re.I)
  def person(m):
   value=m.group("name");clean=value.strip(" .");norm=canon(clean)
   if not clean or norm in {"YOUR","YOUR A","YOU","BANK","MY","SELF","UPI","NEFT","IMPS","RTGS","ACCOUNT","ACCOUNT NO","ACCOUNT NUMBER","ACCT","A/C","CARD","CREDIT CARD","DEBIT CARD","SAVINGS ACCOUNT","CURRENT ACCOUNT","UPI ID","VPA","VPA ID"} or all(w in BANK_WORDS for w in norm.split()) or norm.startswith(("YOUR ACCOUNT","YOUR CARD")):return m.group()
   if norm in self.merchants:return m.group("label")+hold(value)
   business=bool(re.search(r"\b(?:LTD|LIMITED|PVT|PRIVATE|STORE|STORES|SHOP|CAFE|BAKERY|RESTAURANT|SERVICES|TRADERS|ENTERPRISES|SUPERMARKET|PHARMACY|MART)\b",norm))
   if business:
    self.note(index,clean,"Business-looking name preserved; confirm it is a merchant","preserved")
    return m.group("label")+hold(value)
   self.note(index,clean,"Counterparty could be a person or merchant; add to merchant keep-list if needed")
   return m.group("label")+change("person",self.mapper.person(clean))+("." if value.endswith(".") else "")
  body=party.sub(person,body)
  credited=re.compile(r"(?P<label>[;.]\s*)(?P<name>[^\W\d_][\w .&\x27-]{0,60}?)(?=\s+credited\b)",re.I)
  body=credited.sub(person,body)
  # Named greetings occasionally expose the account holder.
  def greeting(m):
   if canon(m.group(2)) in {"CUSTOMER","USER","CARDHOLDER","ACCOUNT HOLDER"}:return m.group()
   self.note(index,m.group(2),"Possible account-holder name in greeting")
   return m.group(1)+change("person",self.mapper.person(m.group(2)))
  body=re.sub(r"(\bDear\s+)([^\W\d_][\w .'-]{0,45}?)(?=[,;:]|$)",greeting,body,flags=re.I)
  # Residual long IDs are safer masked; all ambiguity is recorded privately.
  def residual(m):
   self.note(index,m.group(),"Unclassified long numeric/alphanumeric identifier")
   return change("unclassified_identifier",self.mapper.shape(m.group()))
  body=re.sub(r"(?<!\w)(?:[A-Za-z]*\d){6,}[A-Za-z0-9]*(?!\w)",residual,body)
  # Numbers 4–5 digits outside known amounts/dates may be account tails or OTPs.
  body=re.sub(r"(?<!\w)\d{4,5}(?!\w)",residual,body)
  # Review unhandled title-case word pairs instead of silently declaring safety.
  common={"DEAR CUSTOMER","DEAR USER","YOUR ACCOUNT","YOUR CARD","AVAILABLE BALANCE","UPI REF","REF NO"}
  for m in re.finditer(r"\b[A-Z][a-z]+(?:[ -][A-Z][a-z]+){1,3}\b",body):
   norm=canon(m.group())
   if norm not in common and norm not in self.merchants and not any(w in BANK_WORDS for w in norm.split()):
    self.note(index,m.group(),"Unclassified title-case phrase: inspect before sharing","preserved")
  def restore(m):
   n=0
   for c in m.group(1):n=n*26+ord(c)-64
   return vault[n-1]
  return re.sub("\ue000([A-Z]+)\ue001",restore,body)
 def sender(self,value,index):
  value=value.strip()
  if re.fullmatch(r"[A-Z]{2,3}-[A-Z0-9]{3,15}(?:-[A-Z])?",value):return value
  if value in {"HDFCBK","HDFCBANK","ICICIB","ICICIBANK","SBIINB","SBIUPI","KOTAK","KOTAKB","KOTAKBANK","AXIS","AXISBK","AXISBANK","PAYTM","PHONEPE"}:return value
  if re.fullmatch(r"\d{3,6}",value):
   self.note(index,value,"Numeric sender short code preserved; confirm bank origin","preserved");return value
  if re.fullmatch(r"[+\d ()-]+",value):return self.redact(value,index)
  self.note(index,value,"Unrecognised sender label; could be a contact name")
  return self.mapper.person(value)

def valid_timestamp(value):
 """Accept timestamp layouts, not arbitrary metadata in the date field."""
 text=value.strip()
 if re.fullmatch(r"\d{10}|\d{13}",text):
  try:
   seconds=int(text)/(1000 if len(text)==13 else 1)
   return 1970 <= datetime.fromtimestamp(seconds,timezone.utc).year <= 2100
  except (ValueError,OverflowError,OSError):return False
 try:
  datetime.fromisoformat(text.replace("Z","+00:00"))
  return True
 except ValueError:pass
 clean=re.sub(r"\s+(?:IST|UTC|GMT)$","",text,flags=re.I)
 dates=("%d-%b-%Y","%d-%B-%Y","%d-%m-%Y","%d/%m/%Y","%d-%b-%y",
        "%d-%m-%y","%d/%m/%y","%d %b %Y","%d %B %Y","%b %d, %Y",
        "%B %d, %Y","%d%b%y","%d%b%Y")
 times=(""," %H:%M"," %H:%M:%S"," %I:%M %p"," %I:%M:%S %p")
 for date in dates:
  for clock in times:
   try:datetime.strptime(clean,date+clock);return True
   except ValueError:pass
 return False

def xml_records(text,redactor):
 if re.search(r"<!\s*(?:DOCTYPE|ENTITY)\b",text,re.I):raise ValueError("DOCTYPE/entity declarations are not accepted")
 root=ET.fromstring(text);records=[];claimed=set()
 for node in root.iter():
  if id(node) in claimed:continue
  attrs={keyname(k):(k,v) for k,v in node.attrib.items()}
  children={keyname(c.tag):c for c in node if not list(c)}
  body=next((v[1] for k,v in attrs.items() if k in BODY_KEYS),None)
  bodychild=next((c for k,c in children.items() if k in BODY_KEYS),None)
  if body is None and bodychild is not None:body=bodychild.text or "";claimed.add(id(bodychild))
  if body is None and keyname(node.tag) in {"sms","message","msg"} and not list(node) and node.text and node.text.strip():body=node.text
  if body is None:continue
  index=len(records)+1;sender="";stamp=""
  if bodychild is not None and any(k in BODY_KEYS for k in attrs) and (bodychild.text or "")!=body:
   redactor.note(index,bodychild.text or "","Conflicting XML body child omitted; body attribute selected","omitted")
  for keys,dest in [(SENDER_KEYS,"sender"),(DATE_KEYS,"date")]:
   value=next((v[1] for k,v in attrs.items() if k in keys),None)
   if value is None:value=next((c.text or "" for k,c in children.items() if k in keys),"")
   if dest=="sender":sender=redactor.sender(value,index) if value else ""
   else:
    stamp=value
    if stamp and not valid_timestamp(stamp):
     redactor.note(index,stamp,"Unrecognised timestamp omitted","omitted");stamp=""
  for k,v in node.attrib.items():
   if keyname(k) not in BODY_KEYS|SENDER_KEYS|DATE_KEYS:
    redactor.note(index,v,"Unused XML attribute '"+k+"' omitted","omitted")
  for child in node:
   if keyname(child.tag) not in BODY_KEYS|SENDER_KEYS|DATE_KEYS:
    redactor.note(index,"".join(child.itertext()),"Unused XML element '"+child.tag+"' omitted","omitted")
  records.append({"sender":sender,"date":stamp,"body":redactor.redact(body,index)})
 if not records:raise ValueError("No messages recognised: expected a body/text attribute or child element")
 return records

def process(path,handoff,mapper,merchants=(),encoding=None):
 path=Path(path)
 with path.open("rb") as stream:raw=stream.read(MAX_BYTES+1)
 if len(raw)>MAX_BYTES:raise ValueError("Input exceeds 64 MiB; split the export first")
 if encoding:text=raw.decode(encoding)
 elif raw.startswith((b"\xff\xfe",b"\xfe\xff")):text=raw.decode("utf-16")
 else:text=raw.decode("utf-8-sig")
 redactor=Redactor(mapper,merchants)
 isxml=path.suffix.lower()==".xml" or text.lstrip().startswith("<")
 if isxml:
  rows=xml_records(text,redactor);root=ET.Element("messages",{"redacted":"true","format":"SpendWise-review-v1"})
  for row in rows:
   item=ET.SubElement(root,"message",{k:v for k,v in row.items() if k!="body" and v})
   ET.SubElement(item,"body").text=row["body"]
  ET.indent(root,space="  ");output=ET.tostring(root,encoding="unicode",xml_declaration=True)+"\n";suffix="xml"
 else:
  lines=text.splitlines();rows=[redactor.redact(x,i+1) for i,x in enumerate(lines)]
  output="\n".join(rows)+("\n" if text.endswith(("\n","\r")) else "");suffix="txt"
 target=Path(handoff)/"real-sms/redacted"/("sms-"+mapper.digest(path.name)[:10]+".redacted."+suffix)
 if target.resolve()==path.resolve():raise ValueError("Input and output must differ")
 private=Path(handoff)/"real-sms/private"
 reviewpath=private/(path.name+".review.json")
 report={"input_file":path.name,"output_file":target.name,"messages":len(rows),"status":"HUMAN_REVIEW_REQUIRED",
 "note":"This file contains ORIGINAL uncertain tokens. Do not share it or the private directory.",
 "counts":redactor.counts,"review":redactor.review}
 # Persist the mapping before publishing output so a repeat run stays consistent.
 mapper.save();atomic(reviewpath,json.dumps(report,ensure_ascii=False,indent=2)+"\n")
  # The plain-text review is intentionally private: it contains original tokens.
 reviewtext="PRIVATE REVIEW - DO NOT SHARE\nOutput: "+target.name+"\nMessages: "+str(len(rows))+"\n\n"
 for item in redactor.review:
  reviewtext+="Message "+str(item["message"])+" ["+item["action"]+"] "+item["reason"]+"\n  Original token: "+repr(item["token"])+"\n"
 if not redactor.review:reviewtext+="No uncertain tokens detected. This is not a guarantee: inspect every output message.\n"
 atomic(reviewpath.with_suffix(".txt"),reviewtext);atomic(target,output)
 return target,reviewpath,report

def main(argv=None):
 ap=argparse.ArgumentParser(description=__doc__)
 ap.add_argument("inputs",nargs="+",type=Path,help="One SMS per text line, or an XML SMS export")
 ap.add_argument("--merchants",type=Path,help="UTF-8 file: one merchant name per line to preserve")
 ap.add_argument("--encoding",help="Override input encoding, for example utf-16")
 args=ap.parse_args(argv)
 handoff=Path(__file__).resolve().parent.parent
 try:
  if len({p.stem.lower() for p in args.inputs})!=len(args.inputs):raise ValueError("Input basenames must be distinct")
  keep=args.merchants.read_text(encoding="utf-8-sig").splitlines() if args.merchants else []
  mapper=Mapper(handoff/"real-sms/private/redaction-state.json")
  for path in args.inputs:
   out,review,report=process(path,handoff,mapper,keep,args.encoding)
   print(str(report["messages"])+" messages -> "+str(out))
   print("Private review list ("+str(len(report["review"]))+" items): "+str(review.with_suffix(".txt")))
  print("HUMAN REVIEW REQUIRED: inspect every output before sharing. Share only reviewed redacted copies; never share private/.")
  return 0
 except (OSError,ValueError,ET.ParseError,UnicodeError) as e:
  print("Redaction stopped: "+str(e),file=sys.stderr);return 1
if __name__=="__main__":raise SystemExit(main())
