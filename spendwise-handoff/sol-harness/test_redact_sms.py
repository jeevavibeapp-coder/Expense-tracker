"""Synthetic tests only. No real financial/personal data in these fixtures."""
import importlib.util,json,re,subprocess,sys,tempfile,unittest,xml.etree.ElementTree as ET
from pathlib import Path
HERE=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location("redact_sms",HERE/"redact_sms.py")
mod=importlib.util.module_from_spec(spec);spec.loader.exec_module(mod)
class RedactionTests(unittest.TestCase):
 def setUp(self):
  self.mapper=mod.Mapper(seed="ab"*32)
  self.r=mod.Redactor(self.mapper)
 def test_account_shape_and_repeated_suffix(self):
  x=self.r.redact("A/c XX1234 debited. Card **1234 used. Account 987654321098 credited.")
  a=re.search(r"XX(\d{4})",x)[1];b=re.search(r"\*\*(\d{4})",x)[1]
  self.assertEqual(a,b);self.assertNotEqual(a,"1234")
  self.assertNotIn("987654321098",x)
 def test_distinct_accounts(self):
  a=self.r.redact("A/c XX1234");b=self.r.redact("A/c XX5678")
  self.assertNotEqual(a,b)
 def test_reference_shared_between_messages(self):
  a=self.r.redact("UPI Ref 123456789012")
  b=self.r.redact("UTR 123456789012")
  self.assertEqual(a.split()[-1],b.split()[-1]);self.assertNotIn("123456789012",a+b)
  self.assertRegex(a,r"UPI Ref \d{12}$")
 def test_refno_and_alphanumeric_shape(self):
  x=self.r.redact("Refno AB123456CD UTR 987654321000")
  self.assertRegex(x,r"Refno [A-Z]{2}\d{6}[A-Z]{2} UTR \d{12}$")
  self.assertNotIn("AB123456CD",x)
 def test_amount_and_balance(self):
  x=self.r.redact("INR 1,200.50 debited. Avl Bal: Rs.9,876.54")
  self.assertIn("INR 1,200.50 debited",x);self.assertNotIn("9,876.54",x)
  self.assertRegex(x,r"Avl Bal: Rs\.\d,\d{3}\.\d{2}$")
 def test_dates_times_and_bank_header(self):
  text="VM-HDFCBK INR 100.25 paid to AMAZON on 18-Nov-2026 23:30:45 Ref 123456789012"
  x=self.r.redact(text)
  for s in ["VM-HDFCBK","INR 100.25","AMAZON","18-Nov-2026","23:30:45"]:self.assertIn(s,x)
 def test_large_amount_not_phone(self):
  x=self.r.redact("INR 9876543210.25 debited from account XX1234")
  self.assertIn("INR 9876543210.25",x)
 def test_sbi_currency_free_amount(self):
  x=self.r.redact("A/C X9218 debited by 199.0 on date 08Jul26 trf to SWIGGY")
  self.assertIn("debited by 199.0",x);self.assertIn("08Jul26",x);self.assertIn("SWIGGY",x)
 def test_upi_handles_consistent(self):
  a=self.r.redact("to rahul.sharma@okicici.")
  b=self.r.redact("VPA rahul.sharma@okicici")
  vpa=re.search(r"[\w.]+@okicici",a)[0]
  self.assertIn(vpa,b);self.assertNotIn("rahul.sharma",a+b);self.assertTrue(a.endswith("."))
 def test_unknown_email_is_masked_and_reviewed(self):
  x=self.r.redact("Email person.name@example.com")
  self.assertNotIn("person.name",x);self.assertNotIn("example.com",x)
  self.assertTrue(any("domain" in q["reason"] for q in self.r.review))
 def test_phone_prefix_and_shape(self):
  x=self.r.redact("Call +91 9876543210 or 9123456789")
  self.assertRegex(x,r"Call \+91 [6-9]\d{9} or [6-9]\d{9}")
  self.assertNotIn("9876543210",x);self.assertNotIn("9123456789",x)
 def test_names_and_known_merchants(self):
  a=self.r.redact("INR 200 sent to Rahul Sharma on 18-Nov-2026 via UPI.")
  b=self.r.redact("INR 200 received from RAHUL SHARMA on 19-Nov-2026 via UPI.")
  self.assertIn("PERSON_1",a);self.assertIn("PERSON_1",b);self.assertNotIn("Rahul",a)
  self.assertIn("AMAZON",self.r.redact("INR 500 paid to AMAZON on 18-Nov-2026"))
 def test_explicit_person_paths(self):
  x=self.r.redact("UPI/P2P/123456789012/Rahul Sharma/UPI")
  self.assertRegex(x,r"UPI/P2P/\d{12}/PERSON_1/UPI")
 def test_merchant_paths(self):
  x=self.r.redact("UPI/P2M/123456789012/NEW SHOP/UPI")
  self.assertIn("/NEW SHOP/UPI",x);self.assertNotIn("123456789012",x)
 def test_unknown_merchant_conservative_then_allowlist(self):
  text="INR 100 paid to ACMECO on 18-Nov-2026."
  self.assertIn("PERSON_1",self.r.redact(text))
  r=mod.Redactor(self.mapper,["ACMECO"]);self.assertIn("ACMECO",r.redact(text))
 def test_business_names_reviewed(self):
  x=self.r.redact("INR 50 paid to CORNER BAKERY on 18-Nov-2026")
  self.assertIn("CORNER BAKERY",x)
  self.assertTrue(any(q["action"]=="preserved" for q in self.r.review))
 def test_unknown_numeric_identifier(self):
  x=self.r.redact("Tracking 123456789012 and OTP 123456")
  self.assertNotIn("123456789012",x);self.assertNotIn("123456",x)
  self.assertTrue(self.r.review)
 def test_unicode_digits(self):
  x=self.r.redact("INR १२३.४५ debited from A/c XX१२३४ on १८-Nov-२०२६")
  self.assertIn("INR १२३.४५",x);self.assertIn("१८-Nov-२०२६",x);self.assertNotIn("XX१२३४",x)
 def test_different_people_get_different_ids(self):
  a=self.r.redact("Paid to Rahul Sharma on 18-Nov-2026")
  b=self.r.redact("Paid to Priya Rao on 18-Nov-2026")
  self.assertIn("PERSON_1",a);self.assertIn("PERSON_2",b)
 def test_greeting(self):
  self.assertNotIn("Priya Rao",self.r.redact("Dear Priya Rao, INR 50 credited"))
  self.assertIn("Dear Customer",self.r.redact("Dear Customer, INR 50 credited"))
 def test_state_persistence_without_original_values(self):
  with tempfile.TemporaryDirectory(dir=HERE/"redaction-tests") as d:
   p=Path(d)/"state.json";m=mod.Mapper(p,seed="cd"*32)
   a=mod.Redactor(m).redact("Paid to Rahul Sharma on 18-Nov-2026 Ref 123456789012");m.save()
   state=p.read_text();self.assertNotIn("Rahul",state);self.assertNotIn("123456789012",state)
   b=mod.Redactor(mod.Mapper(p)).redact("Paid to Rahul Sharma on 18-Nov-2026 Ref 123456789012")
   self.assertEqual(a,b)
 def test_xml_attributes_sms_backup_restore_layout(self):
  text='<smses><sms address="VM-HDFCBK" date="1794987000000" contact_name="Priya Rao" body="INR 100 paid to Rahul Sharma on 18-Nov-2026 UTR 123456789012" /></smses>'
  rows=mod.xml_records(text,self.r);self.assertEqual(len(rows),1)
  self.assertEqual(rows[0]["sender"],"VM-HDFCBK");self.assertEqual(rows[0]["date"],"1794987000000")
  self.assertNotIn("Rahul",rows[0]["body"]);self.assertTrue(any(q["token"]=="Priya Rao" for q in self.r.review))
 def test_xml_child_elements_and_namespace(self):
  text='<x:archive xmlns:x="urn:test"><x:message><x:sender>AD-ICICIB</x:sender><x:timestamp>2026-11-18T12:30:45+05:30</x:timestamp><x:body>INR 50 sent to Priya Rao on 18-Nov-2026</x:body><x:contact>A Person</x:contact></x:message></x:archive>'
  rows=mod.xml_records(text,self.r);self.assertEqual(rows[0]["sender"],"AD-ICICIB")
  self.assertEqual(rows[0]["date"],"2026-11-18T12:30:45+05:30");self.assertNotIn("Priya",rows[0]["body"])
 def test_xml_unknown_layout_fails_closed(self):
  with self.assertRaises(ValueError):mod.xml_records("<export><unknown>Rahul Sharma</unknown></export>",self.r)
 def test_xml_entities_rejected(self):
  with self.assertRaises(ValueError):mod.xml_records('<!DOCTYPE x [<!ENTITY a "secret">]><sms body="&a;"/>',self.r)
 def test_output_separates_review_and_drops_metadata(self):
  with tempfile.TemporaryDirectory(dir=HERE/"redaction-tests") as d:
   root=Path(d);p=root/"sample.xml"
   p.write_text('<smses><sms address="+919876543210" body="INR 100 paid to Rahul Sharma on 18-Nov-2026" contact_name="Priya Rao"/></smses>')
   m=mod.Mapper(root/"real-sms/private/redaction-state.json",seed="ef"*32)
   out,review,rep=mod.process(p,root,m)
   self.assertIn("redacted",out.parts);self.assertIn("private",review.parts)
   text=out.read_text();self.assertNotIn("Rahul",text);self.assertNotIn("Priya",text);self.assertNotIn("9876543210",text)
   self.assertIn("Rahul",review.read_text());self.assertEqual(rep["status"],"HUMAN_REVIEW_REQUIRED")
 def test_url_redacted(self):
  x=self.r.redact("Details https://example.com/Rahul?account=1234")
  self.assertNotIn("Rahul",x);self.assertNotIn("1234",x);self.assertIn("https://redacted.invalid/",x)
 def test_plaintext_process_and_utf16_xml(self):
  with tempfile.TemporaryDirectory(dir=HERE/"redaction-tests") as d:
   root=Path(d);p=root/"sample.txt";p.write_text("VM-HDFCBK INR 100 paid to AMAZON on 18-Nov-2026\nA/c XX1234\n",encoding="utf8")
   out,_,rep=mod.process(p,root,self.mapper);self.assertEqual(rep["messages"],2);self.assertIn("AMAZON",out.read_text())
   p=root/"utf16.xml";p.write_text('<smses><sms address="VM-HDFCBK" body="INR 10 sent to Priya Rao on 18-Nov-2026"/></smses>',encoding="utf-16")
   out,_,rep=mod.process(p,root,self.mapper);self.assertEqual(rep["messages"],1)

 def test_by_and_precredit_person(self):
  x=self.r.redact("INR 500 credited to A/c XX1234 by Rahul Sharma on 18-Nov-2026")
  self.assertNotIn("Rahul",x)
  x=self.r.redact("INR 500 transferred; Priya Rao credited. UTR 123456789012")
  self.assertNotIn("Priya",x);self.assertIn("credited",x)
 def test_generic_upi_path(self):
  x=self.r.redact("UPI/CR/123456789012/Rahul Sharma/SBIN")
  self.assertNotIn("Rahul",x);self.assertNotIn("123456789012",x)
  self.assertIn("UPI/CR/",x);self.assertIn("/SBIN",x)
 def test_same_alphanumeric_id_different_case(self):
  a=self.mapper.shape("AB1234CD");b=self.mapper.shape("ab1234cd")
  self.assertEqual(a.lower(),b);self.assertTrue(a[:2].isupper());self.assertTrue(b[:2].islower())
 def test_number_mapping_no_collisions(self):
  values=[self.mapper.number(str(100000+i)) for i in range(500)]
  self.assertEqual(len(values),len(set(values)))
 def test_bad_timestamp_is_omitted_for_review(self):
  rows=mod.xml_records('<message body="INR 100 paid to AMAZON" date="20261118 Rahul Sharma"/>',self.r)
  self.assertEqual(rows[0]["date"],"");self.assertTrue(self.r.review)


 def test_xml_direct_text_body(self):
  rows=mod.xml_records('<archive><sms address="VM-HDFCBK" date="1794987000000">INR 100 paid to AMAZON on 18-Nov-2026</sms></archive>',self.r)
  self.assertEqual(len(rows),1);self.assertIn("AMAZON",rows[0]["body"])
 def test_synthetic_mutations_keep_financial_shape(self):
  for i in range(100):
   account=str(1200+i);reference=str(510000000000+i)
   body="INR 123.45 debited from A/c XX"+account+" to AMAZON on 18-Nov-2026 12:30:45. UTR "+reference+". Avl Bal INR 9,876.54."
   if i%2:body=body.replace(" ","  ")
   out=self.r.redact(body,i)
   self.assertNotIn("XX"+account,out);self.assertNotIn(reference,out);self.assertNotIn("9,876.54",out)
   self.assertIn("123.45",out);self.assertIn("18-Nov-2026",out);self.assertIn("12:30:45",out);self.assertIn("AMAZON",out)
 def test_output_filename_does_not_expose_input_name(self):
  with tempfile.TemporaryDirectory(dir=HERE/"redaction-tests") as d:
   root=Path(d);p=root/"Rahul-9876543210.txt";p.write_text("INR 100 paid to AMAZON")
   out,review,_=mod.process(p,root,self.mapper)
   self.assertNotIn("Rahul",out.name);self.assertNotIn("9876543210",out.name)
   self.assertTrue(review.with_suffix(".txt").exists())


class EndToEndPrivacyRegression(unittest.TestCase):
 def test_person_before_vpa_and_phone(self):
  for text in ("Rs 500 sent to Rahul Sharma at rahul.sharma@okicici on 18-11-2026 13:10.", "Rs 700 received from Rahul Sharma +91 98765 43210 on 18-Nov-2026 15:22."):
   r=mod.Redactor(mod.Mapper(seed="ab"*32));out=r.redact(text)
   self.assertNotIn("Rahul",out);self.assertIn("PERSON_1",out)
   self.assertIn("2026",out)
 def test_upi_path_preserves_trailing_date(self):
  for path in ("UPI/P2M/423456789012/SWIGGY", "UPI/CR/523456789012/Priya Rao", "UPI/P2P/523456789012/Priya Rao"):
   r=mod.Redactor(mod.Mapper(seed="ab"*32))
   out=r.redact("INR 200 credited "+path+" on 18/11/2026 at 18:15.")
   self.assertIn("on 18/11/2026 at 18:15.",out)
   self.assertNotIn("Priya",out)
   if "P2M" in path:self.assertIn("SWIGGY",out)


class EmbeddedIdentifierRegression(unittest.TestCase):
 def test_upi_path_vpa_keeps_shape_and_is_masked(self):
  for prefix in ("UPI/P2M/", "UPI/P2P/", "UPI/CR/"):
   r=mod.Redactor(mod.Mapper(seed="ab"*32))
   out=r.redact("INR 100 debited "+prefix+"123456789012/rahul.sharma@okicici on 18-Nov-2026")
   self.assertNotIn("rahul.sharma",out)
   self.assertRegex(out,r"/[a-z]{5}\.[a-z]{6}@okicici on 18-Nov-2026")
 def test_p2m_embedded_phone_is_masked(self):
  r=mod.Redactor(mod.Mapper(seed="ab"*32))
  out=r.redact("Rs 100 debited UPI/P2M/123456789012/SWIGGY 9876543210 on 18-Nov-2026")
  self.assertIn("SWIGGY",out);self.assertNotIn("9876543210",out)
  self.assertIn("18-Nov-2026",out)
 def test_one_character_upi_handle(self):
  r=mod.Redactor(mod.Mapper(seed="ab"*32))
  out=r.redact("Rs 100 paid to AMAZON at a@ybl.")
  self.assertNotIn("a@ybl",out);self.assertRegex(out,r"[a-z]@ybl\.")


class GrammarPreservationRegression(unittest.TestCase):
 def test_account_and_vpa_labels_are_not_names(self):
  r=mod.Redactor(mod.Mapper(seed="ab"*32))
  out=r.redact("Rs 100 credited to account XX1234 from UPI ID rahul@ybl on 18-Nov-2026")
  self.assertIn("credited to account XX",out)
  self.assertIn("from UPI ID ",out)
  self.assertNotIn("rahul@ybl",out)
  self.assertNotIn("XX1234",out)


class XmlMetadataPrivacyRegression(unittest.TestCase):
 def test_sender_cannot_hide_pii_behind_bank_substring(self):
  r=mod.Redactor(mod.Mapper(seed="ab"*32))
  rows=mod.xml_records('<sms body="INR 100 paid to AMAZON" address="RAVI HDFCBK 9876543210"/>',r)
  self.assertEqual(rows[0]["sender"],"PERSON_1")
  self.assertTrue(any("RAVI" in x["token"] for x in r.review))
 def test_date_cannot_carry_extra_phone(self):
  r=mod.Redactor(mod.Mapper(seed="ab"*32))
  rows=mod.xml_records('<sms body="INR 100 paid to AMAZON" date="18-11-2026 9876543210"/>',r)
  self.assertEqual(rows[0]["date"],"")
  self.assertTrue(any("9876543210" in x["token"] for x in r.review))
 def test_valid_timestamp_layouts(self):
  for text in ("1795000000000","1795000000","2026-11-18T14:20:00+05:30","18-Nov-2026 15:00","Nov 18, 2026 3:20:00 PM","18 Nov 2026 15:00 IST"):
   self.assertTrue(mod.valid_timestamp(text),text)
  for text in ("9876543210","18-11-2026 9876543210","2026-99-99","Rahul Sharma"):
   self.assertFalse(mod.valid_timestamp(text),text)


class CounterpartyReviewRegression(unittest.TestCase):
 def test_bank_word_does_not_hide_person(self):
  r=mod.Redactor(mod.Mapper(seed="ab"*32))
  out=r.redact("INR 500.00 credited to A/c 1234567890 by RAVI BANK Ref 123456789012.")
  self.assertNotIn("RAVI BANK",out);self.assertIn("PERSON_",out)
  out=r.redact("INR 100 credited by HDFC BANK on 18-Nov-2026")
  self.assertIn("HDFC BANK",out)
 def test_unknown_at_counterparty_and_merchants(self):
  for name in ("RAVI KUMAR","ravi kumar"):
   r=mod.Redactor(mod.Mapper(seed="ab"*32))
   out=r.redact("INR 100 paid at "+name+" on 18-Nov-2026")
   self.assertNotIn(name,out);self.assertTrue(r.review)
  r=mod.Redactor(mod.Mapper(seed="ab"*32))
  out=r.redact("INR 100 paid at AMAZON on 18-Nov-2026")
  self.assertIn("AMAZON",out)


class FinalPrivacyEdgeCases(unittest.TestCase):
 def test_schemeless_urls_are_masked_and_reviewed(self):
  for url in ("www.bank.example/RAVI-KUMAR/token-A1B2", "bit.ly/RAVI2026"):
   r=mod.Redactor(mod.Mapper(seed="ab"*32))
   out=r.redact("Rs 100 debited. Details "+url)
   self.assertNotIn("RAVI",out);self.assertNotIn(url,out);self.assertTrue(r.review)
   self.assertIn("Rs 100 debited",out)
 def test_balance_sign_before_currency(self):
  for balance in ("Available Balance: -Rs.2,000.50", "Avl Bal +INR 5,000.20"):
   r=mod.Redactor(mod.Mapper(seed="ab"*32))
   out=r.redact("Rs 100 debited. "+balance)
   self.assertNotIn("2,000.50",out);self.assertNotIn("5,000.20",out)
   self.assertIn("Rs 100 debited",out)
 def test_greeting_case_variations(self):
  for text in ("dear ravi kumar, Rs 100 debited", "DEAR RAVI KUMAR, Rs 100 debited"):
   r=mod.Redactor(mod.Mapper(seed="ab"*32))
   out=r.redact(text)
   self.assertNotIn("ravi",out.lower());self.assertIn("PERSON_1",out)


class ExplicitHolderAndXmlConflict(unittest.TestCase):
 def test_account_and_card_holder_names(self):
  for label in ("Account holder","Cardholder name","Card holder"):
   r=mod.Redactor(mod.Mapper(seed="ab"*32))
   out=r.redact(label+": RAVI KUMAR. INR 500.00 debited to AMAZON.")
   self.assertNotIn("RAVI KUMAR",out);self.assertTrue(r.review)
   self.assertIn("INR 500.00",out);self.assertIn("AMAZON",out)
 def test_conflicting_xml_body_is_flagged(self):
  r=mod.Redactor(mod.Mapper(seed="ab"*32))
  rows=mod.xml_records('<sms body="INR 500 paid to AMAZON"><body>INR 900 paid to SWIGGY</body></sms>',r)
  self.assertIn("INR 500",rows[0]["body"])
  self.assertTrue(any("Conflicting XML" in x["reason"] and "INR 900" in x["token"] for x in r.review))

if __name__=="__main__":
 (HERE/"redaction-tests").mkdir(exist_ok=True)
 unittest.main(verbosity=2)
