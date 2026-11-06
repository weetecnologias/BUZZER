"""Development seed: demo data for all four roles, requests in every lifecycle state, finance and jobs."""
import random
from datetime import datetime, timedelta, timezone

from .db import insert, jdump, next_ref, now, one, q, run, uid, update, val
from .events import DEFAULT_TEMPLATES
from .security import hash_password
from .seed_data import (ASSIGNMENT_RULES, CATEGORIES, GENERIC_HOW, GENERIC_PREPARE, HELP, INTEGRATIONS, MODULES,
                        PLANS, REVIEW_COMMENTS, SETTINGS, SLA, WORKFLOWS, f)
from .workflow import refresh_workload

DEMO_PASSWORD = "Buzzer@123"
NOW = datetime.now(timezone.utc)
FMT = "%Y-%m-%dT%H:%M:%SZ"


def ts(days=0.0, hours=0.0):
    return (NOW - timedelta(days=days, hours=hours)).strftime(FMT)


def ahead(days=0.0, hours=0.0):
    return (NOW + timedelta(days=days, hours=hours)).strftime(FMT)


COLORS = ["#BEEB5D", "#9BD3AE", "#F2C14E", "#8FB8ED", "#F4A9A0", "#C7B8EA"]

STAFF = [  # key, name, email, mobile, roles, display, specialty
    ("super", "Anita Rao", "super@buzzer.in", "+919000000010", ["super_admin"], None, None),
    ("ops", "Vikram Shah", "ops@buzzer.in", "+919000000011", ["ops_admin"], None, None),
    ("fin", "Meghna Das", "finance@buzzer.in", "+919000000012", ["finance_admin"], None, None),
    ("ver", "Imran Qureshi", "verify@buzzer.in", "+919000000013", ["verification_reviewer"], None, None),
    ("priya", "Priya Sharma", "priya@buzzer.in", "+919000000001", ["agent"], "Priya", "Home & lifestyle"),
    ("ravi", "Ravi Kumar", "ravi@buzzer.in", "+919000000002", ["agent"], "Ravi", "Home repairs"),
    ("meena", "Meena Iyer", "meena@buzzer.in", "+919000000003", ["agent"], "Meena", "Household & errands"),
    ("sai", "Sai Teja", "sai@buzzer.in", "+919000000004", ["agent"], "Sai", "Events & photography"),
    ("kiran", "Kiran Reddy", "kiran@buzzer.in", "+919000000005", ["agent"], "Kiran", "Weddings & events"),
    ("neha", "Neha Kapoor", "neha@buzzer.in", "+919000000006", ["team_lead", "agent"], "Neha", "Team lead"),
]
CAPACITY = {"ravi": 8, "meena": 6, "neha": 20}
AVAIL = {"sai": "busy", "kiran": "available"}

CUSTOMERS = [  # key, name, mobile, agent, backup, locality, address
    ("rahul", "Rahul Sharma", "+919876543210", "priya", "ravi", "Kondapur", "Flat 402, Aditya Heights, Kondapur"),
    ("ananya", "Ananya Iyer", "+919876500001", "ravi", "priya", "Madhapur", "12-4-55, Image Gardens Road, Madhapur"),
    ("mohammed", "Mohammed Irfan", "+919876500002", "ravi", "meena", "Kukatpally", "H.No 7-2/3, KPHB Colony, Kukatpally"),
    ("sunita", "Sunita Reddy", "+919876500003", "meena", "sai", "Gachibowli", "Villa 18, Lanco Hills, Gachibowli"),
    ("karthik", "Karthik Varma", "+919876500004", "sai", "kiran", "Jubilee Hills", "Plot 221, Road No. 36, Jubilee Hills"),
    ("divya", "Divya Menon", "+919876500005", "priya", "meena", "Hitec City", "Tower C-1203, My Home Avatar, Hitec City"),
    ("arjun", "Arjun Patel", "+919876500006", "kiran", "ravi", "Begumpet", "5-9-22, Prakash Nagar, Begumpet"),
    ("lakshmi", "Lakshmi Narayan", "+919876500007", "meena", "priya", "Manikonda", "Flat 3B, Sri Residency, Manikonda"),
    ("farah", "Farah Khan", "+919876500008", "ravi", "sai", "Tolichowki", "8-1-284, Tolichowki Main Road"),
    ("suresh", "Suresh Babu", "+919876500009", "kiran", "neha", "Banjara Hills", "Road No. 12, Banjara Hills"),
]

PROVIDERS = [  # key, name, type, kind, locality, areas, category slugs, owner(name, email, mobile)|None, verification, status, source
    ("abc", "ABC Home Services", "business", "general", "Kondapur", ["Kondapur", "Gachibowli", "Madhapur", "Hitec City", "Manikonda"], ["home-repairs", "household-family"], ("Arun Kumar", "arun@abchome.in", "+919848012345"), "verified", "active", "internal_directory"),
    ("sai_el", "Sri Sai Electricals", "individual", "general", "Kukatpally", ["Kukatpally", "Miyapur", "Nizampet", "Kondapur"], ["home-repairs"], ("Venkat Rao", "venkat@saielectricals.in", "+919848022222"), "verified", "active", "internal_directory"),
    ("aqua", "AquaFix Plumbing", "business", "general", "Madhapur", ["Madhapur", "Hitec City", "Gachibowli", "Jubilee Hills"], ["home-repairs"], ("Sandeep Goud", "sandeep@aquafix.in", "+919848033333"), "verified", "active", "internal_directory"),
    ("cool", "CoolBreeze AC Care", "business", "general", "Gachibowli", ["Gachibowli", "Kondapur", "Madhapur", "Manikonda"], ["home-repairs"], None, "verified", "active", "internal_directory"),
    ("swift", "SwiftMove Packers & Movers", "business", "general", "Secunderabad", ["Secunderabad", "Begumpet", "Kukatpally", "Uppal"], ["home-shifting", "delivery-logistics"], None, "verified", "active", "internal_directory"),
    ("road", "RoadRunner Drivers", "business", "general", "Ameerpet", ["Ameerpet", "Kondapur", "Madhapur", "Banjara Hills", "Gachibowli"], ["travel-transport"], None, "verified", "active", "internal_directory"),
    ("citycabs", "CityCabs Hyderabad", "business", "general", "Begumpet", ["Begumpet", "Secunderabad", "Kondapur", "Hitec City"], ["travel-transport"], None, "verified", "active", "internal_directory"),
    ("sparkle", "Sparkle Home Cleaners", "business", "general", "Kondapur", ["Kondapur", "Gachibowli", "Madhapur", "Hitec City"], ["household-family"], ("Lata Devi", "lata@sparkle.in", "+919848044444"), "verified", "active", "internal_directory"),
    ("royal", "Royal Feast Caterers", "business", "catering", "Banjara Hills", ["Banjara Hills", "Jubilee Hills", "Begumpet", "Gachibowli"], ["wedding-catering", "events-celebrations"], ("Mahesh Naidu", "mahesh@royalfeast.in", "+919848055555"), "verified", "active", "internal_directory"),
    ("lens", "Lens & Light Studios", "business", "photography", "Jubilee Hills", ["Jubilee Hills", "Madhapur", "Banjara Hills", "Gachibowli"], ["photography-videography"], ("Nisha Verma", "nisha@lensandlight.in", "+919848066666"), "verified", "active", "internal_directory"),
    ("candid", "Candid Frames", "individual", "photography", "Madhapur", ["Madhapur", "Kondapur", "Hitec City"], ["photography-videography"], None, "verified", "active", "internal_directory"),
    ("celeb", "Celebrate Events Co.", "business", "general", "Begumpet", ["Begumpet", "Secunderabad", "Banjara Hills", "Gachibowli"], ["events-celebrations", "wedding-catering"], None, "verified", "active", "internal_directory"),
    ("auto", "AutoCare Garage", "business", "general", "Kukatpally", ["Kukatpally", "Miyapur", "Kondapur"], ["vehicle-services"], None, "verified", "active", "internal_directory"),
    ("quick", "QuickDeliver Logistics", "business", "general", "Uppal", ["Uppal", "Secunderabad", "Begumpet", "Kukatpally"], ["delivery-logistics", "daily-errands"], None, "verified", "active", "internal_directory"),
    ("errand", "ErrandPro Helpers", "individual", "general", "Dilsukhnagar", ["Dilsukhnagar", "Kothapet", "Uppal"], ["daily-errands"], None, "unverified", "active", "internal_directory"),
    ("sourcing", "BestBuy Sourcing Desk", "business", "general", "Ameerpet", ["Ameerpet", "Banjara Hills", "Begumpet"], ["shopping-sourcing"], None, "verified", "active", "internal_directory"),
    ("realty", "Hyderabad Homes Realty", "business", "general", "Banjara Hills", ["Banjara Hills", "Jubilee Hills", "Gachibowli", "Madhapur"], ["real-estate"], None, "verified", "active", "internal_directory"),
    ("metro", "Metro Interiors & Repairs", "business", "general", "Tolichowki", ["Tolichowki", "Manikonda", "Gachibowli"], ["home-repairs", "home-shifting"], ("Rizwan Ali", "rizwan@metrointeriors.in", "+919848077777"), "pending", "active", "self_registered"),
    ("fly", "FlyByNight Movers", "business", "general", "Uppal", ["Uppal"], ["home-shifting"], None, "verified", "suspended", "internal_directory"),
]

PRICE = {"home-repairs": (600, 3500), "household-family": (800, 3000), "home-shifting": (7000, 22000), "travel-transport": (700, 5000),
         "events-celebrations": (15000, 90000), "wedding-catering": (150000, 600000), "photography-videography": (20000, 120000),
         "vehicle-services": (1500, 8000), "delivery-logistics": (300, 1500), "daily-errands": (300, 1200),
         "shopping-sourcing": (500, 5000), "real-estate": (5000, 30000), "special-requests": (1000, 8000)}
CATPROV = {"home-repairs": ["abc", "sai_el", "aqua", "cool", "metro"], "household-family": ["sparkle", "abc"], "home-shifting": ["swift", "metro"],
           "travel-transport": ["road", "citycabs"], "events-celebrations": ["celeb", "royal"], "wedding-catering": ["royal", "celeb"],
           "photography-videography": ["lens", "candid"], "vehicle-services": ["auto"], "delivery-logistics": ["quick", "swift"],
           "daily-errands": ["quick", "errand"], "shopping-sourcing": ["sourcing"], "real-estate": ["realty"], "special-requests": ["celeb"]}

# (customer, category, subcategory, description, days_ago, target, providers, options)
R = [
    ("rahul", "home-repairs", "Electrician", "Switchboard sparks when I plug in the iron. Need an electrician to check the wiring and replace the faulty MCB.", 1.1, "awaiting_approval", ["abc", "sai_el"], {"prices": [1250, 1450], "details": {"issue_type": "Electrical", "property_type": "Apartment"}}),
    ("rahul", "travel-transport", "Airport drop / pickup", "Need a sedan to drop me at Shamshabad airport tomorrow 5 AM. 2 passengers, 3 bags.", 0.4, "finding_providers", ["road", "citycabs"], {"details": {"trip_type": "Airport drop", "pickup_point": "Aditya Heights, Kondapur", "passengers": "2"}}),
    ("rahul", "household-family", "Deep cleaning", "2 BHK deep cleaning including kitchen, bathrooms and balcony.", 20, "completed", ["sparkle", "abc"], {"prices": [2400, 2900], "review": 5}),
    ("rahul", "home-repairs", "Plumbing", "Kitchen tap is leaking continuously. Need it fixed or replaced.", 35, "completed", ["abc", "aqua"], {"prices": [850, 1100], "review": 5}),
    ("rahul", "vehicle-services", "Car service", "Full service for my Swift, due at 20,000 km.", 12, "cancelled", ["auto"], {"stage": "finding_providers", "reason": "Changed my plan - will do it next month"}),
    ("ananya", "home-repairs", "AC service & repair", "Split AC is not cooling and makes noise. Needs service and gas refill.", 3, "confirmed", ["abc", "cool"], {"prices": [1499, 1750], "paid": "deposit", "sched": 1.1}),
    ("ananya", "events-celebrations", "Birthday party", "Birthday party for 40 guests at a banquet hall in Madhapur. Need decor and entertainment.", 6, "quotes_received", ["celeb", "royal"], {"details": {"event_type": "Birthday", "guests": "40"}}),
    ("ananya", "home-repairs", "Painting", "Repaint two bedrooms, light colours. Walls need minor putty work.", 0.3, "finding_providers", ["abc", "metro", "cool"], {}),
    ("ananya", "home-repairs", "Plumbing", "Bathroom flush tank not filling properly.", 29, "completed", ["abc", "aqua"], {"prices": [1100, 1300], "review": 4}),
    ("mohammed", "home-repairs", "Electrician", "Install two ceiling fans and replace tube lights in hall.", 2, "in_progress", ["abc", "sai_el"], {"prices": [1800, 2100], "paid": "deposit"}),
    ("mohammed", "travel-transport", "Drivers", "Need a driver for my daily commute KPHB to Hitec City for one month.", 15, "completed", ["road", "citycabs"], {"prices": [18500, 21000], "review": 4}),
    ("mohammed", "delivery-logistics", "Same-day delivery", "Deliver a sealed parcel from KPHB to Secunderabad today.", 8, "completed", ["quick", "swift"], {"prices": [450, 600], "review": 5}),
    ("mohammed", "home-repairs", "Plumbing", "Water pipe burst in the bathroom - urgent!", 0.05, "finding_providers", ["abc", "aqua"], {"urgent": True}),
    ("sunita", "home-shifting", "Packing & moving", "Shifting a 3 BHK from Gachibowli to Kondapur, around 20 km. Need packing and unpacking.", 5, "awaiting_approval", ["swift", "metro"], {"prices": [14500, 16800], "details": {"from_locality": "Gachibowli", "to_locality": "Kondapur", "home_size": "3 BHK"}}),
    ("sunita", "household-family", "Cook", "Need a vegetarian cook for 2 meals a day, 6 days a week.", 30, "completed", ["sparkle"], {"prices": [9000], "review": 5}),
    ("sunita", "special-requests", "Something else", "Arrange a surprise anniversary dinner for 10 people on a rooftop in Jubilee Hills.", 1, "submitted", [], {}),
    ("sunita", "home-repairs", "Carpentry", "Repair a broken wardrobe door and fix two loose drawers.", 37, "completed", ["abc", "metro"], {"prices": [2600, 2900], "review": 4}),
    ("karthik", "photography-videography", "Wedding photography", "Candid photo + video coverage for our wedding reception. About 300 guests.", 9, "finding_providers", ["lens", "candid"], {"brief": "photography"}),
    ("karthik", "home-repairs", "Painting", "Repaint the living room and passage. Asian Paints royale preferred.", 25, "completed", ["abc", "metro"], {"prices": [6800, 7400], "review": 5}),
    ("karthik", "vehicle-services", "Car cleaning", "Interior detailing and exterior polish for my Fortuner.", 4, "disputed", ["auto"], {"prices": [3200], "paid": "full", "dispute": "Interior still has stains and the polish was uneven."}),
    ("divya", "household-family", "Home cleaning", "Regular weekly cleaning for a 2 BHK apartment.", 18, "completed", ["sparkle", "abc"], {"prices": [1400, 1600], "review": 5}),
    ("divya", "shopping-sourcing", "Find a product", "Find the best price for a 55 inch OLED TV with 3-year warranty.", 3, "under_review", [], {"details": {"product": "55 inch OLED TV"}}),
    ("divya", "real-estate", "Rent a home", "Looking to rent a 2 BHK in Gachibowli up to Rs 32,000 per month.", 10, "expired", ["realty"], {"details": {"intent": "Rent", "property_type": "Apartment"}}),
    ("divya", "home-repairs", "Carpentry", "Need a carpenter to build a shoe rack and fix kitchen cabinet hinges.", 0.2, "finding_providers", ["abc", "sai_el"], {}),
    ("divya", "home-repairs", "Appliance repair", "Washing machine drum makes a loud noise during spin.", 13, "completed", ["abc", "cool"], {"prices": [2200, 2600], "review": 4}),
    ("arjun", "events-celebrations", "Corporate event", "Annual team day for 80 employees with lunch and activities.", 14, "completed", ["celeb", "royal"], {"prices": [45000, 52000], "review": 5}),
    ("arjun", "delivery-logistics", "Parcel pickup", "Pickup documents from Begumpet and deliver to Hitec City office.", 0.1, "submitted", [], {"unassigned": True}),
    ("arjun", "home-repairs", "AC service & repair", "Two ACs need annual service before summer.", 7, "completed", ["cool", "abc"], {"prices": [1800, 2000], "review": 5}),
    ("arjun", "home-repairs", "Electrician", "Fix flickering lights and replace two switches.", 21, "completed", ["abc", "sai_el"], {"prices": [950, 1200], "review": 5}),
    ("lakshmi", "daily-errands", "Pickup & drop", "Pick up a prescription and groceries from the market and deliver by evening.", 2, "quotes_received", ["quick", "errand"], {}),
    ("lakshmi", "home-repairs", "Plumbing", "Replace kitchen sink mixer and fix a dripping bathroom shower.", 40, "completed", ["aqua", "abc"], {"prices": [1700, 2000], "review": 5}),
    ("lakshmi", "special-requests", "Something else", "Find a local artist to paint a mural on a nursery wall.", 22, "rejected", [], {"reason": "Outside current service scope - we could not find a verified provider."}),
    ("lakshmi", "household-family", "Home cleaning", "Post-renovation cleaning of a 2 BHK flat.", 44, "completed", ["abc", "sparkle"], {"prices": [1500, 1900], "review": 4}),
    ("farah", "household-family", "Cook", "Need a cook for lunch and dinner, mostly North Indian.", 6, "confirmed", ["sparkle", "abc"], {"prices": [8500, 9200], "paid": "none", "sched": 2}),
    ("farah", "home-repairs", "Electrician", "Install an inverter and rewire two points.", 28, "completed", ["sai_el", "abc"], {"prices": [3800, 4200], "review": 5}),
    ("farah", "wedding-catering", "Catering only", "Wedding catering for 250 guests - mix of veg and non-veg, South Indian menu.", 11, "quotes_received", ["royal", "celeb"], {"brief": "wedding_catering"}),
    ("farah", "home-repairs", "Appliance repair", "Refrigerator is not cooling and has water leaking at the back.", 33, "completed", ["abc", "cool"], {"prices": [1800, 2300], "review": 4}),
    ("suresh", "wedding-catering", "Full wedding planning", "Full wedding planning for 400 guests in Banjara Hills - venue, decor, catering and coordination.", 7, "awaiting_approval", ["royal", "celeb"], {"prices": [640000, 720000], "brief": "wedding"}),
    ("suresh", "home-shifting", "Packing & moving", "Shifting a 2 BHK to Jubilee Hills next week.", 16, "cancelled", ["swift", "metro"], {"prices": [11500, 12800], "stage": "confirmed", "paid": "deposit", "reason": "Postponed the move by a month"}),
    ("suresh", "vehicle-services", "Car service", "Periodic service and brake pad replacement.", 26, "completed", ["auto"], {"prices": [5400], "review": 5}),
    ("suresh", "home-repairs", "Plumbing", "Overhead tank overflow and a leaking inlet valve.", 9, "completed", ["abc", "aqua"], {"prices": [3400, 3800], "review": 5}),
]

OFFSETS = {"under_review": 1.5, "finding_providers": 4, "quotes_received": 18, "awaiting_approval": 22, "confirmed": 40, "in_progress": 70, "completed": 96}
ORDER = ["submitted", "under_review", "finding_providers", "quotes_received", "awaiting_approval", "confirmed", "in_progress", "completed"]
STATUS_REASON = {"under_review": "Agent started review", "finding_providers": "Invited providers to quote", "quotes_received": "First quote received",
                 "awaiting_approval": "Quotes validated and shared", "confirmed": "Customer approved quote", "in_progress": "Provider started the job",
                 "completed": "Customer confirmed completion"}


def seed():
    rnd = random.Random(11)
    pw = hash_password(DEMO_PASSWORD)  # one hash reused for all demo accounts
    T = now

    # ------------------------------------------------------------ catalogue
    for i, (mid, name, desc) in enumerate(MODULES):
        insert("modules", id=mid, name=name, description=desc, sort=i)
    wf = {}
    for code, name, desc, stages, labels in WORKFLOWS:
        tid = uid()
        wf[code] = tid
        insert("workflow_templates", id=tid, code=code, name=name, description=desc, stages_json=jdump(stages))
        for i, s in enumerate(ORDER):
            insert("workflow_steps", id=uid(), template_id=tid, seq=i + 1, status_key=s,
                   label=labels.get(s, s.replace("_", " ").capitalize()), sla_hours=SLA[s], approval_gate=1 if s == "awaiting_approval" else 0)
    cat = {}
    for i, (slug, name, mod, icon, color, phase, active, wcode, desc, kw, subs, schema, includes, prep) in enumerate(CATEGORIES):
        cid = uid()
        cat[slug] = cid
        insert("categories", id=cid, slug=slug, name=name, module_id=mod, description=desc, icon=icon, color=color, phase=phase,
               is_active=active, workflow_template_id=wf[wcode], schema_json=jdump(schema), includes_json=jdump(includes),
               how_json=jdump(GENERIC_HOW), prepare_json=jdump(prep or GENERIC_PREPARE), keywords=kw, sort=i + 1)
        for j, s in enumerate(subs):
            insert("subcategories", id=uid(), category_id=cid, name=s, keywords=s.lower(), sort=j)
    sub = {(r["category_id"], r["name"]): r["id"] for r in q("SELECT id, category_id, name FROM subcategories")}

    for k, v, label, group in SETTINGS:
        insert("settings", key=k, value=v, label=label, group_name=group, updated_at=T())
    for name, kind, status, notes in INTEGRATIONS:
        insert("integrations", id=uid(), name=name, kind=kind, status=status, notes=notes, last_checked_at=ts(0, 2))
    for name, rtype, desc, cfg, prio in ASSIGNMENT_RULES:
        insert("assignment_rules", id=uid(), name=name, rule_type=rtype, description=desc, config_json=jdump(cfg), priority=prio, active=1)
    plan = {}
    for i, (code, name, pm, py, benefits, sort) in enumerate(PLANS):
        pid = uid()
        plan[code] = pid
        insert("plans", id=pid, code=code, name=name, audience="customer", price_month=pm, price_year=py, benefits_json=jdump(benefits), active=1, sort=sort)
    for code, name, aud, title, body in DEFAULT_TEMPLATES:
        insert("message_templates", id=uid(), code=code, name=name, channel="in_app", audience=aud, title=title, body=body, active=1, updated_at=T())
    for title, topic, aud, body in HELP:
        slug = "-".join("".join(ch for ch in title.lower() if ch.isalnum() or ch == " ").split())[:60]
        insert("help_articles", id=uid(), slug=slug, title=title, body=body, audience=aud, topic=topic, published=1, updated_at=ts(5))
    insert("commission_rules", version=1, rate=10.0, note="Launch rate", effective_from=ts(120), created_by=None)
    insert("commission_rules", version=2, rate=8.0, note="Pilot rate for Hyderabad launch", effective_from=ts(60), created_by=None)

    # ----------------------------------------------------------- staff & agents
    U = {}
    for key, name, email, mobile, roles, display, spec in STAFF:
        U[key] = uid()
        insert("users", id=U[key], name=name, email=email, mobile=mobile, password_hash=pw, status="active", language="en",
               avatar_color=rnd.choice(COLORS), created_at=ts(100), last_login_at=ts(0, rnd.randint(1, 20)))
        for r in roles:
            insert("user_roles", user_id=U[key], role=r)
        if display:
            insert("agents", user_id=U[key], display_name=display, team_lead_id=U["neha"] if key != "neha" and "neha" in U else None,
                   specialty=spec, phone=mobile, is_pool=0 if key == "neha" else 1, created_at=ts(100))
            insert("agent_workloads", agent_id=U[key], active_requests=0, capacity_limit=CAPACITY.get(key, 40),
                   availability_status=AVAIL.get(key, "available"))
    for k in ("priya", "ravi", "meena", "sai", "kiran"):
        run("UPDATE agents SET team_lead_id=? WHERE user_id=?", (U["neha"], U[k]))

    # ---------------------------------------------------------------- customers
    C, ADDR = {}, {}
    for key, name, mobile, agent, backup, loc, line in CUSTOMERS:
        C[key] = uid()
        insert("users", id=C[key], name=name, mobile=mobile, status="active", language="en", avatar_color=rnd.choice(COLORS),
               created_at=ts(rnd.randint(50, 90)), last_login_at=ts(0, rnd.randint(1, 40)))
        insert("user_roles", user_id=C[key], role="customer")
        insert("customers", user_id=C[key], notification_prefs=jdump({"whatsapp": key in ("rahul", "ananya")}), created_at=ts(60))
        for kind in ("terms", "privacy"):
            insert("consents", user_id=C[key], kind=kind, granted=1, version="1.0", updated_at=ts(60))
        if key in ("rahul", "ananya"):
            insert("consents", user_id=C[key], kind="whatsapp", granted=1, version="1.0", updated_at=ts(50))
        ADDR[key] = uid()
        insert("addresses", id=ADDR[key], user_id=C[key], label="Home", line1=line, locality=loc, landmark="Near main road", city="Hyderabad",
               pincode="500084", is_default=1, created_at=ts(60))
        insert("customer_agent_assignments", customer_id=C[key], primary_agent_id=U[agent], backup_agent_id=U[backup], status="active",
               assigned_at=ts(60), assigned_by="system", reason="New customer - dedicated agent allocated")
        insert("assignment_history", id=uid(), customer_id=C[key], from_agent_id=None, to_agent_id=U[agent], reason="New customer - dedicated agent allocated",
               changed_by="system", changed_at=ts(60))
        cid = uid()
        insert("conversations", id=cid, kind="customer_agent", customer_id=C[key], agent_id=U[agent], created_at=ts(60), updated_at=ts(60))
    insert("addresses", id=uid(), user_id=C["rahul"], label="Office", line1="Level 5, Cyber Pearl, Hitec City", locality="Hitec City",
           landmark="Opp. Mindspace", city="Hyderabad", pincode="500081", is_default=0, created_at=ts(40))

    # ---------------------------------------------------------------- providers
    P, PU = {}, {}
    for key, name, ptype, kind, loc, areas, cats, owner, ver, status, source in PROVIDERS:
        P[key] = uid()
        uid_ = None
        if owner:
            uid_ = uid()
            PU[key] = uid_
            insert("users", id=uid_, name=owner[0], email=owner[1], mobile=owner[2], password_hash=pw, status="active", language="en",
                   avatar_color=rnd.choice(COLORS), created_at=ts(80), last_login_at=ts(0, 3))
            insert("user_roles", user_id=uid_, role="provider")
            insert("consents", user_id=uid_, kind="provider_verification", granted=1, version="1.0", updated_at=ts(80))
        insert("providers", id=P[key], user_id=uid_, business_name=name, type=ptype, kind=kind, description=f"{name} - trusted local service provider in Hyderabad.",
               phone=owner[2] if owner else f"+9198480{rnd.randint(10000, 99999)}", email=owner[1] if owner else None, address=f"{loc}, Hyderabad",
               locality=loc, verification_status=ver, status=status, rating_avg=0, rating_count=0, jobs_completed=0,
               response_rate=rnd.randint(82, 98), commission_rate=None, upi_id=(f"{key}@upi" if owner and ver == "verified" else None),
               bank_masked=("XXXX" + str(rnd.randint(1000, 9999)) if owner and ver == "verified" else None), source=source,
               logo_color=rnd.choice(COLORS), created_at=ts(rnd.randint(30, 90)))
        for a in areas:
            insert("provider_areas", id=uid(), provider_id=P[key], locality=a)
        for slug in cats:
            for sname in [s["name"] for s in q("SELECT name FROM subcategories WHERE category_id=?", (cat[slug],))][:3]:
                lo, hi = PRICE.get(slug, (500, 3000))
                insert("provider_services", id=uid(), provider_id=P[key], category_id=cat[slug], name=sname, base_price=rnd.randint(lo, hi) // 100 * 100,
                       unit="per job", active=1)
        if ver == "verified":
            for t, num in (("id_proof", "XXXX" + str(rnd.randint(1000, 9999))), ("address_proof", None)):
                insert("provider_documents", id=uid(), provider_id=P[key], doc_type=t, doc_number=num, status="verified",
                       reviewed_by=U["ver"], reviewed_at=ts(60), created_at=ts(70))
        for wd in range(7):
            insert("provider_availability", id=uid(), provider_id=P[key], weekday=wd, start_time="09:00", end_time="18:00", active=1 if wd < 6 else 0)
    # partner pending verification: documents waiting in the admin queue
    for t in ("id_proof", "address_proof"):
        insert("provider_documents", id=uid(), provider_id=P["metro"], doc_type=t, doc_number="XXXX4521" if t == "id_proof" else None, status="pending", created_at=ts(1))
    insert("provider_documents", id=uid(), provider_id=P["lens"], doc_type="gst", doc_number="XXXX9983", status="pending", created_at=ts(0, 8))
    insert("provider_documents", id=uid(), provider_id=P["abc"], doc_type="business_photos", status="verified", reviewed_by=U["ver"], reviewed_at=ts(55), created_at=ts(60))
    insert("provider_documents", id=uid(), provider_id=P["royal"], doc_type="licence", doc_number="XXXX1120", status="verified", expires_at=ahead(20), reviewed_by=U["ver"], reviewed_at=ts(50), created_at=ts(55))
    for d in ("2026-11-14", "2026-11-22"):
        insert("provider_blocked_dates", id=uid(), provider_id=P["abc"], date=d, reason="Family function")
    # packages / portfolio
    for nm, price, det in (("Classic Veg Feast", 650, {"per_plate": "Rs 650", "min_guests": "100", "max_guests": "800", "menu": "Welcome drink, 2 starters, 3 curries, biryani, 2 desserts"}),
                           ("Grand Wedding Spread", 1150, {"per_plate": "Rs 1,150", "min_guests": "200", "max_guests": "1500", "menu": "Live counters, 4 starters, 5 curries, 2 biryanis, 4 desserts"}),
                           ("Corporate Lunch Box", 320, {"per_plate": "Rs 320", "min_guests": "30", "max_guests": "300", "menu": "Veg thali with sweet"})):
        insert("provider_packages", id=uid(), provider_id=P["royal"], kind="catering", name=nm, price=price, details_json=jdump(det), active=1)
    for nm, price, det in (("Candid Essentials", 45000, {"coverage": "6 hours", "team": "1 photographer + 1 assistant", "deliverables": "400 edited photos, online gallery"}),
                           ("Wedding Signature", 95000, {"coverage": "2 days", "team": "2 photographers + 1 videographer", "deliverables": "800 edited photos, 6-min highlight film, premium album"}),
                           ("Pre-wedding Shoot", 28000, {"coverage": "4 hours", "team": "1 photographer", "deliverables": "60 edited photos, 1 reel"})):
        insert("provider_packages", id=uid(), provider_id=P["lens"], kind="photography", name=nm, price=price, details_json=jdump(det), active=1)
    for i, t in enumerate(("Temple wedding - Banjara Hills", "Pre-wedding at Golconda", "Baby shower candids", "Corporate gala", "Haldi ceremony", "Engagement portraits")):
        insert("portfolio_items", id=uid(), provider_id=P["lens"], title=t, caption="Shot by Lens & Light", color=COLORS[i % len(COLORS)], created_at=ts(10 + i))

    # --------------------------------------------------------------- membership
    mid = uid()
    insert("memberships", id=mid, user_id=C["rahul"], plan_id=plan["plus"], interval="month", status="active", started_at=ts(45), renewal_at="2026-12-15T00:00:00Z")
    for d, amt in ((45, 499), (15, 499)):
        insert("payment_transactions", id=uid(), reference=next_ref("pay", "PAY"), user_id=C["rahul"], kind="membership", method="upi", amount=amt,
               status="success", gateway_ref="sandbox-" + uid()[:8], memo="BUZZER Plus (monthly)", created_at=ts(d))
        insert("ledger_entries", id=uid(), account="membership_revenue", credit=amt, memo="BUZZER Plus subscription", created_at=ts(d))
    insert("memberships", id=uid(), user_id=C["ananya"], plan_id=plan["plus"], interval="year", status="active", started_at=ts(30), renewal_at=ahead(335))

    # ----------------------------------------------------------------- requests
    build_requests(rnd, U, C, ADDR, P, PU, cat, sub, wf)

    # ------------------------------------------------- employers, jobs, candidates
    build_jobs(rnd, U, C, PU, P)

    # ----------------------------------------------- comms, tasks, complaints, etc.
    build_extras(rnd, U, C, P, PU)

    # --------------------------------------------------- final rollups
    for p in q("SELECT id FROM providers"):
        r = one("SELECT AVG(rating) AS a, COUNT(*) AS n FROM reviews WHERE subject_type='provider' AND subject_id=?", (p["id"],))
        n = val("SELECT COUNT(*) FROM bookings WHERE provider_id=? AND status='completed'", (p["id"],), 0)
        update("providers", "id", p["id"], rating_avg=round(r["a"], 1) if r["a"] else 0, rating_count=r["n"], jobs_completed=n)
    for a in q("SELECT user_id FROM agents"):
        refresh_workload(a["user_id"])
    # backfill sample timestamps so lists look natural
    run("UPDATE sessions SET revoked=1")


def build_requests(rnd, U, C, ADDR, P, PU, cat, sub, wf):
    from .api.quotes import _settle_commission
    cust_agent = {r["customer_id"]: r["primary_agent_id"] for r in q("SELECT customer_id, primary_agent_id FROM customer_agent_assignments")}
    cat_by_slug = {r["slug"]: r for r in q("SELECT * FROM categories")}
    prov_user = {pid: P[k] for k, pid in PU.items()}

    for spec in R:
        ck, slug, subname, desc, days, target, provs, o = spec
        c = cat_by_slug[slug]
        created_dt = NOW - timedelta(days=days)
        created = created_dt.strftime(FMT)
        rid = uid()
        agent = None if o.get("unassigned") else cust_agent[C[ck]]
        stage = o.get("stage") or target
        if target in ("rejected",):
            path = ["submitted", "under_review"]
        elif target == "expired":
            path = ["submitted", "under_review", "finding_providers"]
        elif target == "disputed":
            path = ORDER[:7]
        elif target == "cancelled":
            path = ORDER[: ORDER.index(stage) + 1]
        else:
            path = ORDER[: ORDER.index(target) + 1]
        loc = rnd.choice([r["locality"] for r in q("SELECT locality FROM addresses WHERE user_id=?", (C[ck],))])
        # timestamps along the path, never in the future
        times = {}
        for s in path:
            off = OFFSETS.get(s, 0)
            times[s] = min(created_dt + timedelta(hours=off * (0.6 + 0.5 * rnd.random())), NOW - timedelta(minutes=3))
        final = target
        last_t = max(times.values()) if times else created_dt
        end_dt = min(last_t + timedelta(hours=3), NOW - timedelta(minutes=1)) if final not in ORDER else last_t
        preferred = (created_dt + timedelta(days=2)).strftime(FMT) if days < 10 else None
        ref = next_ref("request", "BZ")
        insert("requests", id=rid, reference=ref, customer_id=C[ck], assigned_agent_id=agent, category_id=c["id"],
               subcategory_id=sub.get((c["id"], subname)), workflow_template_id=c["workflow_template_id"], status=final,
               priority="urgent" if o.get("urgent") else "normal", service_address_id=ADDR[ck], locality=loc, preferred_at=preferred,
               budget_min=None, budget_max=None, title=subname, description=desc, fingerprint=uid(), created_at=created,
               updated_at=end_dt.strftime(FMT), status_changed_at=end_dt.strftime(FMT),
               first_response_at=(times["under_review"].strftime(FMT) if "under_review" in times and agent else None),
               cancel_reason=o.get("reason") if final in ("cancelled", "rejected", "expired") else None,
               closed_at=end_dt.strftime(FMT) if final in ("completed", "cancelled", "rejected", "expired") else None)
        for k, v in (o.get("details") or {}).items():
            fld = next((x for x in jload_schema(c) if x["key"] == k), None)
            insert("request_details", id=uid(), request_id=rid, field_key=k, field_label=fld["label"] if fld else k, value=str(v))
        # history
        prev = None
        for s in path:
            insert("request_status_history", id=uid(), request_id=rid, from_status=prev, to_status=s,
                   changed_by=(C[ck] if s == "submitted" else (agent or U["neha"])), reason="Request created" if s == "submitted" else STATUS_REASON.get(s),
                   created_at=times[s].strftime(FMT))
            prev = s
        if final in ("cancelled", "rejected", "expired", "disputed"):
            insert("request_status_history", id=uid(), request_id=rid, from_status=prev, to_status=final, changed_by=(C[ck] if final == "cancelled" else (agent or U["neha"])),
                   reason=o.get("reason") or o.get("dispute") or "Closed", created_at=end_dt.strftime(FMT))
        # assignment trail
        insert("assignment_history", id=uid(), customer_id=C[ck], request_id=rid, from_agent_id=None, to_agent_id=agent,
               reason="Dedicated primary agent" if agent else "No agent available - queued for team lead", changed_by="system", changed_at=created)
        # brief
        if o.get("brief"):
            kind = "photography" if o["brief"] == "photography" else "wedding"
            insert("event_briefs", request_id=rid, kind=kind, event_type="Wedding" if kind == "wedding" else "Wedding reception",
                   event_date=ahead(75 if o["brief"] == "wedding" else 50)[:10], venue="Function hall, Banjara Hills", locality=loc,
                   guest_count=400 if o["brief"] == "wedding" else (250 if o["brief"] == "wedding_catering" else 300), budget_min=600000 if o["brief"] == "wedding" else 150000,
                   budget_max=900000 if o["brief"] == "wedding" else 400000, services="Planning, Decor, Catering, Photo & video" if o["brief"] == "wedding" else "Catering",
                   coverage_hours=10 if kind == "photography" else None, coverage_style="Candid + traditional" if kind == "photography" else None,
                   deliverables="Edited photos, highlight film, album" if kind == "photography" else None, package_pref="Signature" if kind == "photography" else None,
                   notes="South Indian menu, 60% vegetarian." if o["brief"] == "wedding_catering" else "Please share portfolio and package options.")
            if o["brief"] == "wedding":
                for i, (t, dd, st) in enumerate((("Finalise venue", 60, "done"), ("Catering tasting", 45, "in_progress"), ("Decor concept approval", 35, "pending"), ("Photographer booking", 30, "pending"))):
                    insert("event_milestones", id=uid(), request_id=rid, title=t, due_date=ahead(dd)[:10] if st != "done" else ts(2)[:10], status=st,
                           provider_id=P["royal"] if "Catering" in t else None, created_at=created)
        # invites
        stage_i = ORDER.index(stage) if stage in ORDER else ORDER.index(path[-1])
        quoted_now = stage_i >= ORDER.index("quotes_received") or target in ("disputed",)
        for i, pk in enumerate(provs):
            if stage_i < ORDER.index("finding_providers") and target not in ("expired",):
                break
            quoted = quoted_now and (not o.get("prices") or i < len(o["prices"])) and target not in ("expired",)
            insert("request_invites", id=uid(), request_id=rid, provider_id=P[pk], invited_by=agent or U["neha"],
                   status="quoted" if quoted else "invited", created_at=times.get("finding_providers", created_dt).strftime(FMT),
                   responded_at=times["quotes_received"].strftime(FMT) if quoted and "quotes_received" in times else None)
            if PU.get(pk):
                pass
        # quotes
        winner = None
        if quoted_now and provs:
            lo, hi = PRICE[slug]
            prices = o.get("prices") or [round(rnd.uniform(lo, hi) / 50) * 50 for _ in provs]
            for i, pk in enumerate(provs[: len(prices)]):
                total = prices[i]
                qid = uid()
                shared_stage = ORDER.index("awaiting_approval")
                if stage_i >= ORDER.index("confirmed") or target == "disputed":
                    st = "approved" if i == 0 else "not_selected"
                elif stage_i == shared_stage:
                    st = "shared"
                elif target == "cancelled" and stage_i > ORDER.index("quotes_received"):
                    st = "withdrawn"
                else:
                    st = "submitted"
                qt = times.get("quotes_received", created_dt)
                insert("quotations", id=qid, request_id=rid, provider_id=P[pk], submitted_by=PU.get(pk) or agent, edited_by=PU.get(pk) or agent, status=st,
                       version=1, total=total, scope=f"{subname} as described - labour, standard fittings and clean-up included.",
                       exclusions="Spare parts beyond the listed items, civil work and permissions.", terms="Payment as per this quote; any balance is due on completion.",
                       warranty_days=30 if slug in ("home-repairs", "household-family", "vehicle-services") else 0, deposit_pct=20 if total >= 1500 else 0,
                       valid_until=ahead(5), est_start_at=ahead(1, 2), est_duration="2-3 hours" if total < 5000 else "1-2 days",
                       shared_at=(times.get("awaiting_approval", qt).strftime(FMT) if st in ("shared", "approved", "not_selected") else None),
                       shared_by=agent if st in ("shared", "approved", "not_selected") else None,
                       decided_at=times["confirmed"].strftime(FMT) if st in ("approved", "not_selected") and "confirmed" in times else None,
                       created_at=qt.strftime(FMT), updated_at=qt.strftime(FMT))
                parts = [("Visit & inspection", 0.12), ("Labour / service charge", 0.6), ("Materials / consumables", 0.28)]
                for desc_i, frac in parts:
                    amt = round(total * frac)
                    insert("quotation_items", id=uid(), quotation_id=qid, description=desc_i, qty=1, unit_price=amt, amount=amt)
                if i == 0:
                    winner = (qid, pk, total)
        # booking
        if winner and (stage_i >= ORDER.index("confirmed") or target == "disputed") and target not in ("rejected",):
            qid, pk, total = winner
            bstatus = {"confirmed": "confirmed", "in_progress": "in_progress", "completed": "completed", "disputed": "disputed"}.get(target, "confirmed")
            if target == "cancelled":
                bstatus = "cancelled"
            sched = ahead(o["sched"]) if o.get("sched") else (created_dt + timedelta(days=2)).strftime(FMT)
            if target in ("completed", "disputed", "in_progress"):
                sched = min(created_dt + timedelta(days=2), NOW - timedelta(hours=2)).strftime(FMT)
            dep = round(total * (20 if total >= 1500 else 0) / 100)
            pay = o.get("paid", "full" if target == "completed" else "none")
            paid_amt = total if pay == "full" else (dep if pay == "deposit" else 0)
            bid = uid()
            bt = times.get("confirmed", created_dt).strftime(FMT)
            insert("bookings", id=bid, reference=next_ref("booking", "BK"), request_id=rid, quotation_id=qid, provider_id=P[pk], customer_id=C[ck], agent_id=agent,
                   status=bstatus, scheduled_at=sched, amount=total, deposit_amount=dep, paid_amount=paid_amt,
                   payment_status="paid" if paid_amt >= total else ("deposit_paid" if paid_amt else "unpaid"),
                   cancel_reason=o.get("reason") if bstatus == "cancelled" else None, refund_eligible=1 if (bstatus == "cancelled" and paid_amt) else 0,
                   completion_note="Work completed as per scope. Area cleaned." if target in ("completed", "disputed") else None,
                   completed_at=times["completed"].strftime(FMT) if target == "completed" else None,
                   customer_confirmed_at=times["completed"].strftime(FMT) if target == "completed" else None, created_at=bt, updated_at=end_dt.strftime(FMT))
            insert("booking_events", id=uid(), booking_id=bid, event="confirmed", detail=f"Customer approved quote of Rs {total:,}", actor_id=C[ck], created_at=bt)
            if paid_amt:
                ref = next_ref("pay", "PAY")
                insert("payment_transactions", id=uid(), reference=ref, booking_id=bid, user_id=C[ck], kind="customer_payment", method=rnd.choice(["upi", "card", "upi"]),
                       amount=paid_amt, status="success", gateway_ref="sandbox-" + uid()[:8], created_at=bt)
                insert("ledger_entries", id=uid(), booking_id=bid, provider_id=P[pk], account="escrow", credit=paid_amt, memo=f"Customer payment {ref}", created_at=bt)
                insert("booking_events", id=uid(), booking_id=bid, event="payment", detail=f"Rs {paid_amt:,} via upi", actor_id=C[ck], created_at=bt)
            if target in ("in_progress", "completed", "disputed"):
                insert("booking_events", id=uid(), booking_id=bid, event="in_progress", detail=None, actor_id=PU.get(pk) or agent, created_at=times["in_progress"].strftime(FMT))
            if target == "completed":
                insert("booking_events", id=uid(), booking_id=bid, event="work_done", detail="Work completed as per scope.", actor_id=PU.get(pk) or agent, created_at=times["completed"].strftime(FMT))
                insert("booking_events", id=uid(), booking_id=bid, event="completed", detail="Customer confirmed completion", actor_id=C[ck], created_at=times["completed"].strftime(FMT))
                b = one("SELECT * FROM bookings WHERE id=?", (bid,))
                _settle_commission(b)
                done_at = times["completed"].strftime(FMT)
                run("UPDATE commissions SET created_at=? WHERE booking_id=?", (done_at, bid))
                run("UPDATE ledger_entries SET created_at=? WHERE booking_id=? AND account<>'escrow' OR (booking_id=? AND debit>0)", (done_at, bid, bid))
                rating = o.get("review", 5)
                insert("reviews", id=uid(), booking_id=bid, request_id=rid, reviewer_id=C[ck], subject_type="provider", subject_id=P[pk], rating=rating,
                       comment=rnd.choice(REVIEW_COMMENTS), created_at=min(times["completed"] + timedelta(hours=5), NOW).strftime(FMT))
                if agent:
                    insert("reviews", id=uid(), booking_id=bid, request_id=rid, reviewer_id=C[ck], subject_type="agent", subject_id=agent,
                           rating=rnd.choice([4, 5, 5]), comment="Always responsive.", created_at=min(times["completed"] + timedelta(hours=5), NOW).strftime(FMT))
            if target == "cancelled" and paid_amt:
                insert("booking_events", id=uid(), booking_id=bid, event="cancelled", detail=o.get("reason"), actor_id=C[ck], created_at=end_dt.strftime(FMT))
                insert("refunds", id=uid(), booking_id=bid, requested_by=C[ck], amount=paid_amt, reason=o.get("reason"), status="requested", created_at=end_dt.strftime(FMT))
            if target == "disputed":
                cid = uid()
                insert("complaints", id=cid, reference=next_ref("complaint", "TKT", 5), kind="dispute", request_id=rid, booking_id=bid, raised_by=C[ck], raised_by_role="customer",
                       against_type="provider", against_id=P[pk], topic="Quality of work", description=o["dispute"], status="investigating", priority="normal",
                       owner_id=agent, sla_due_at=ts(0, 4), created_at=end_dt.strftime(FMT))
                insert("complaint_events", id=uid(), complaint_id=cid, actor_id=C[ck], event="opened", note=o["dispute"], created_at=end_dt.strftime(FMT))
                insert("complaint_events", id=uid(), complaint_id=cid, actor_id=agent, event="investigating", note="Reviewing photos with the provider.", created_at=ts(0, 6))
        if final == "cancelled" and stage in ("finding_providers", "quotes_received"):
            pass


def jload_schema(c):
    from .db import jload
    return jload(c["schema_json"], [])


def build_jobs(rnd, U, C, PU, P):
    emp = {}
    for key, uid_, company, industry, loc, contact, phone, ver in (
            ("abc", PU["abc"], "ABC Home Services", "Home services", "Kondapur", "Arun Kumar", "+919848012345", "verified"),
            ("fresh", None, "Fresh Mart Retail", "Retail", "Kukatpally", "Hema Latha", "+919848099001", "verified"),
            ("quick", None, "QuickHire Staffing", "Staffing", "Secunderabad", "Rohit Jain", "+919848099002", "pending")):
        if uid_ is None:
            uid_ = uid()
            insert("users", id=uid_, name=contact, email=f"hr@{key}.in", mobile=phone,
                   password_hash=val("SELECT password_hash FROM users WHERE email='arun@abchome.in'"),
                   status="active", language="en", avatar_color=rnd.choice(COLORS), created_at=ts(60), last_login_at=ts(1))
            insert("user_roles", user_id=uid_, role="employer")
        else:
            insert("user_roles", user_id=uid_, role="employer")
        emp[key] = uid()
        insert("employers", id=emp[key], user_id=uid_, company_name=company, industry=industry, locality=loc, contact_name=contact, phone=phone, verification_status=ver, created_at=ts(55))
    V = [  # emp, title, role, loc, smin, smax, freq, type, shift, days, off, exp, skills, status, days_ago, openings, reason
        ("fresh", "Sales Executive (Retail)", "Sales", "Kukatpally", 10000, 25000, "month", "full_time", "Day (9am-7pm)", "Mon-Sat", "Sunday", "0-2 years", "Customer handling, billing", "published", 6, 2, None),
        ("fresh", "Front Desk Executive", "Reception", "Banjara Hills", 20000, 28000, "month", "full_time", "Day (9am-6pm)", "Mon-Sat", "Sunday", "1-3 years", "Communication, MS Office", "published", 5, 1, None),
        ("fresh", "Store Cashier", "Shopkeeper", "Madhapur", 14000, 18000, "month", "full_time", "Rotational", "6 days", "Rotational", "0-1 year", "Billing, POS", "published", 9, 2, None),
        ("fresh", "Warehouse Helper", "Helper", "Kukatpally", 12000, 15000, "month", "full_time", "Day", "Mon-Sat", "Sunday", "Fresher", "Lifting, stock handling", "published", 12, 4, None),
        ("fresh", "Delivery Executive (Temporary)", "Delivery", "Gachibowli", 800, 1200, "day", "temporary", "Flexible", "Festive season", "-", "Fresher", "Own two-wheeler", "published", 3, 6, None),
        ("abc", "Driver (Personal)", "Driver", "Jubilee Hills", 20000, 25000, "month", "full_time", "Day (8am-6pm)", "Mon-Sat", "Sunday", "3+ years", "Valid licence, city knowledge", "published", 4, 1, None),
        ("abc", "Electrician Helper", "Helper", "Kondapur", 13000, 16000, "month", "full_time", "Day", "Mon-Sat", "Sunday", "Fresher", "Basic wiring", "published", 8, 2, None),
        ("abc", "Customer Support Associate", "Office assistant", "Madhapur", 16000, 20000, "month", "full_time", "Day", "Mon-Fri", "Sat-Sun", "1+ year", "Telugu, Hindi, English", "pending_verification", 1, 1, None),
        ("fresh", "Receptionist (Night)", "Reception", "Hitec City", 18000, 22000, "month", "full_time", "Night", "Mon-Sat", "Sunday", "1+ year", "Communication", "pending_verification", 0.5, 1, None),
        ("quick", "Shop Assistant", "Shopkeeper", "Secunderabad", 11000, 14000, "month", "full_time", "Day", "Mon-Sat", "Sunday", "Fresher", "Customer handling", "pending_verification", 2, 3, None),
        ("quick", "Data Entry Operator", "Office assistant", "Uppal", 9000, 12000, "month", "full_time", "Day", "Mon-Sat", "Sunday", "Fresher", "Typing", "rejected", 15, 5, "Asks candidates for a registration fee. BUZZER does not allow recruitment fees."),
    ]
    vid = {}
    for e, title, role, loc, smin, smax, freq, et, shift, days_, off, exp, skills, status, ago, openings, reason in V:
        v = uid()
        vid[title] = v
        insert("vacancies", id=v, reference=next_ref("vacancy", "JOB", 5), employer_id=emp[e], title=title, role_type=role,
               description=f"{title} at {loc}. Join a friendly team. Salary paid on time with clear working hours. Training provided for freshers.",
               locality=loc, salary_min=smin, salary_max=smax, pay_frequency=freq, employment_type=et, shift=shift, working_days=days_, weekly_off=off,
               experience=exp, skills=skills, deadline=ahead(20)[:10], openings=openings, recruitment_fee_declared=0, status=status,
               verified_by=U["ver"] if status in ("published", "rejected") else None, verified_at=ts(ago - 0.2) if status in ("published", "rejected") else None,
               rejection_reason=reason, created_at=ts(ago))
    cands = {}
    for key, headline, skills, exp, locs, sal, shift, consent in (
            ("karthik", "Experienced personal driver", "Driving, Navigation, Vehicle care", 6, "Jubilee Hills, Banjara Hills", 22000, "Day", 1),
            ("divya", "Retail sales and customer service", "Billing, Customer handling, Hindi, Telugu", 2, "Hitec City, Kukatpally", 18000, "Day", 1),
            ("lakshmi", "Reception and office admin", "MS Office, Communication", 3, "Manikonda, Gachibowli", 20000, "Day", 1),
            ("farah", "Fresher - warehouse and helper roles", "Lifting, Stock handling", 0, "Kukatpally", 13000, "Rotational", 1),
            ("rahul", "Customer support", "Communication", 1, "Kondapur", None, None, 0)):
        cands[key] = uid()
        insert("candidates", id=cands[key], user_id=C[key], headline=headline, skills=skills, experience_years=exp, preferred_localities=locs,
               expected_salary=sal, shift_pref=shift, data_sharing_consent=consent, created_at=ts(20))
    A = [("karthik", "Driver (Personal)", "shortlisted", 3), ("divya", "Sales Executive (Retail)", "interview", 4), ("lakshmi", "Front Desk Executive", "applied", 2),
         ("farah", "Warehouse Helper", "hired", 10), ("divya", "Store Cashier", "applied", 5), ("farah", "Electrician Helper", "applied", 1), ("lakshmi", "Store Cashier", "rejected", 6)]
    for ck, title, st, ago in A:
        aid = uid()
        insert("job_applications", id=aid, vacancy_id=vid[title], candidate_id=cands[ck], status=st, consent_shared=1, hiring_outcome="Joined on 1st of the month" if st == "hired" else None,
               applied_at=ts(ago), updated_at=ts(ago - 1 if ago > 1 else 0.5))
        if st == "interview":
            insert("interviews", id=uid(), application_id=aid, scheduled_at=ahead(2, 3), mode="in_person", location="Fresh Mart HQ, Kukatpally", status="scheduled", notes="Carry ID proof")
    run("UPDATE vacancies SET status='filled' WHERE title='Warehouse Helper' AND 0")
    insert("saved_jobs", candidate_id=cands["divya"], vacancy_id=vid["Front Desk Executive"], created_at=ts(2))
    insert("saved_jobs", candidate_id=cands["rahul"], vacancy_id=vid["Sales Executive (Retail)"], created_at=ts(1))
    insert("job_reports", id=uid(), vacancy_id=vid["Delivery Executive (Temporary)"], reporter_id=C["lakshmi"], reason="Recruiter asked me to pay Rs 500 for a uniform before joining.", status="open", created_at=ts(0, 20))


def build_extras(rnd, U, C, P, PU):
    def msg(conv, sender, body, hours_ago, req=None):
        insert("messages", id=uid(), conversation_id=conv, sender_id=sender, body=body, request_id=req, created_at=ts(0, hours_ago))
    rahul_conv = val("SELECT id FROM conversations WHERE kind='customer_agent' AND customer_id=?", (C["rahul"],))
    r1 = one("SELECT id FROM requests WHERE customer_id=? AND title='Electrician'", (C["rahul"],))["id"]
    for sender, body, h in ((C["rahul"], "Hi Priya, the switchboard in my kitchen sparks when I use the iron.", 30), (U["priya"], "Hi Rahul! Thanks for the details - I'm contacting two verified electricians now.", 29.5),
                            (U["priya"], "Two quotes are ready: ABC Home Services at Rs 1,250 and Sri Sai Electricals at Rs 1,450. Please compare and approve whichever suits you.", 22),
                            (C["rahul"], "Does Rs 1,250 include the new MCB?", 21), (U["priya"], "Yes - the MCB and labour are both included. Parts beyond that are excluded.", 20.5)):
        msg(rahul_conv, sender, body, h, r1)
    run("UPDATE conversations SET updated_at=? WHERE id=?", (ts(0, 20.5), rahul_conv))
    cid = uid()
    insert("conversations", id=cid, kind="provider_agent", provider_id=P["abc"], agent_id=U["priya"], request_id=r1, created_at=ts(1), updated_at=ts(0, 22))
    msg(cid, U["priya"], "Hi Arun, new lead BZ - electrician at Kondapur. Can you quote today?", 28, r1)
    msg(cid, PU["abc"], "Sure Priya, sending a quote in 30 minutes.", 27.5, r1)
    msg(cid, U["priya"], "Thanks. Please include a warranty if possible.", 27, r1)
    # tasks
    for who, title, due, pr, req in (("priya", "Follow up with Rahul on quote approval", ahead(0, 3), "high", r1), ("priya", "Confirm airport driver for tomorrow 5 AM", ahead(0, 1), "high", None),
                                      ("priya", "Share weekly summary with team lead", ahead(1), "normal", None), ("priya", "Call Divya about carpentry quote", ts(0, 2), "normal", None),
                                      ("ravi", "Check AC technician arrival for Ananya", ahead(0, 4), "high", None), ("ravi", "Chase 2 providers for birthday quotes", ahead(0, 6), "normal", None)):
        insert("tasks", id=uid(), agent_id=U[who], request_id=req, customer_id=C["rahul"] if req else None, title=title, due_at=due, priority=pr, status="open", created_at=ts(0, 8))
    insert("tasks", id=uid(), agent_id=U["priya"], title="Update CRM notes for Divya", due_at=ts(1), priority="low", status="done", created_at=ts(2), completed_at=ts(1))
    # contact log
    for ch, party, name, outcome, notes, h in (("call", "provider", "ABC Home Services", "Accepted - will quote", "Confirmed availability for Thursday.", 28), ("whatsapp", "provider", "Sri Sai Electricals", "Quote received", "Shared photos of the switchboard.", 27),
                                              ("call", "customer", "Rahul Sharma", "Informed", "Explained the two quotes and the warranty.", 20)):
        insert("contact_log", id=uid(), request_id=r1, agent_id=U["priya"], party_type=party, party_id=P["abc"] if party == "provider" and "ABC" in name else None, party_name=name,
               channel=ch, direction="outbound", outcome=outcome, notes=notes, created_at=ts(0, h))
    # notifications for the demo customer, agent, partner
    for typ, ev, title, body, mins, link, read in (
            ("request", "request.status", "Request update", "Your request BZ is now: Waiting for your approval.", 5, "#/c/requests", 0),
            ("quote", "quote.shared", "Quote received", "You have 2 new quotes for your electrician request.", 12, "#/c/requests", 0),
            ("booking", "booking.confirmed", "Booking confirmed", "Electrician service booked for today, 5 PM.", 20, "#/c/requests", 1),
            ("payment", "payment.success", "Payment successful", "Rs 1,250 paid for your request.", 25, "#/c/requests", 1),
            ("request", "request.status", "Request update", "Your driver request is in progress.", 60, "#/c/requests", 1),
            ("request", "request.status", "Rate your service", "How was your recent service? Tap to rate.", 180, "#/c/support", 1)):
        insert("notifications", id=uid(), user_id=C["rahul"], type=typ, event=ev, title=title, body=body, link=link, channel="in_app", delivery_status="delivered", is_read=read,
               created_at=ts(0, mins / 60))
    for typ, ev, title, body, mins, link in (("request", "request.new_for_agent", "New request", "Home repairs request from Ananya needs triage.", 15, "#/a/requests"),
                                             ("quote", "quote.received", "Quote received", "ABC Home Services quoted Rs 1,250.", 90, "#/a/quotes"),
                                             ("message", "message.new", "New message", "Rahul: Does Rs 1,250 include the new MCB?", 200, "#/a/messages")):
        insert("notifications", id=uid(), user_id=U["priya"], type=typ, event=ev, title=title, body=body, link=link, channel="in_app", delivery_status="delivered", is_read=0, created_at=ts(0, mins / 60))
    for typ, ev, title, body, mins, link in (("lead", "lead.new", "New lead", "Plumbing in Kukatpally - urgent. Send your quote now.", 3, "#/p/leads"),
                                             ("lead", "lead.new", "New lead", "Carpentry in Hitec City. Respond today.", 12, "#/p/leads"),
                                             ("booking", "quote.approved", "Quote approved", "AC service booking confirmed for tomorrow.", 150, "#/p/bookings"),
                                             ("payout", "payout.paid", "Payout processed", "Rs 25,000 was sent to your bank account.", 60 * 20, "#/p/earnings")):
        insert("notifications", id=uid(), user_id=PU["abc"], type=typ, event=ev, title=title, body=body, link=link, channel="in_app", delivery_status="delivered", is_read=0 if mins < 200 else 1, created_at=ts(0, mins / 60))
    # money: payouts
    insert("payouts", id=uid(), provider_id=P["abc"], amount=25000, status="paid", reference=next_ref("payout", "PO"), requested_at=ts(20), paid_at=ts(18), decided_by=U["fin"])
    insert("ledger_entries", id=uid(), provider_id=P["abc"], account="provider_payable", debit=25000, memo="Payout", created_at=ts(18))
    avail = val("SELECT COALESCE(SUM(credit)-SUM(debit),0) FROM ledger_entries WHERE provider_id=? AND account='provider_payable'", (P["abc"],), 0)
    if avail > 3000:
        insert("payouts", id=uid(), provider_id=P["abc"], amount=min(3000, avail), status="requested", requested_at=ts(0, 20))
    # a processed refund for demo
    b = one("SELECT * FROM bookings WHERE status='cancelled' ORDER BY created_at LIMIT 1")
    # complaints
    r_done = one("SELECT r.id, bk.id AS bid, bk.provider_id FROM requests r JOIN bookings bk ON bk.request_id=r.id WHERE r.customer_id=? AND r.status='completed' ORDER BY r.created_at DESC LIMIT 1", (C["divya"],))
    if r_done:
        c1 = uid()
        insert("complaints", id=c1, reference=next_ref("complaint", "TKT", 5), kind="complaint", request_id=r_done["id"], booking_id=r_done["bid"], raised_by=C["divya"], raised_by_role="customer",
               against_type="provider", against_id=r_done["provider_id"], topic="Late or no-show", description="The cleaner arrived 90 minutes late without informing us.", status="resolved",
               priority="normal", owner_id=U["priya"], sla_due_at=ts(8), resolution="Provider apologised and credited a free add-on service on the next visit.", created_at=ts(10), resolved_at=ts(9))
        insert("complaint_events", id=uid(), complaint_id=c1, actor_id=C["divya"], event="opened", note="Cleaner arrived 90 minutes late.", created_at=ts(10))
        insert("complaint_events", id=uid(), complaint_id=c1, actor_id=U["priya"], event="resolved", note="Free add-on service on next visit.", created_at=ts(9))
    c2 = uid()
    insert("complaints", id=c2, reference=next_ref("complaint", "TKT", 5), kind="complaint", raised_by=C["mohammed"], raised_by_role="customer", topic="Billing / refund",
           description="I was charged twice for the delivery on 28 Oct. Please check and refund the duplicate payment.", status="open", priority="high", owner_id=U["ravi"], sla_due_at=ts(0, 6), created_at=ts(1, 6))
    insert("complaint_events", id=uid(), complaint_id=c2, actor_id=C["mohammed"], event="opened", note="Charged twice for delivery.", created_at=ts(1, 6))
    c3 = uid()
    insert("complaints", id=c3, reference=next_ref("complaint", "TKT", 5), kind="partner_issue", raised_by=PU["abc"], raised_by_role="provider", topic="Payout delay",
           description="My last payout request is still pending after 5 working days.", status="escalated", priority="high", owner_id=U["neha"], sla_due_at=ahead(0, 10), created_at=ts(2))
    insert("complaint_events", id=uid(), complaint_id=c3, actor_id=PU["abc"], event="opened", note="Payout pending 5 days.", created_at=ts(2))
    insert("complaint_events", id=uid(), complaint_id=c3, actor_id=U["priya"], event="escalated", note="Escalated to finance via team lead.", created_at=ts(1))
    # audit history (spread over recent days)
    acts = [("auth.login", "user", U["super"], "super_admin"), ("category.update", "category", None, "admin"), ("provider.verify", "provider", P["abc"], "admin"),
            ("commission.rule", "commission_rule", "2", "admin"), ("user.view", "user", C["rahul"], "admin"), ("refund.decide", "refund", None, "admin"),
            ("request.assign", "request", None, "agent"), ("quote.share", "quotation", None, "agent"), ("customer.view", "user", C["divya"], "agent"),
            ("settings.update", "settings", None, "admin"), ("booking.cancel", "booking", None, "customer"), ("partner.register", "provider", P["metro"], "partner")]
    actors = [U["super"], U["ops"], U["fin"], U["ver"], U["priya"], U["ravi"]]
    for i in range(40):
        a = acts[i % len(acts)]
        insert("audit_logs", id=uid(), actor_id=actors[i % len(actors)], actor_role=a[3], action=a[0], entity_type=a[1], entity_id=a[2],
               detail=None, ip="127.0.0.1", created_at=ts(i * 0.35))
