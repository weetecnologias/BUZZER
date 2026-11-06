"""Static catalogue and platform configuration used to seed a fresh database."""


def f(key, label, type_="text", req=False, opts=None, ph=None):
    d = {"key": key, "label": label, "type": type_, "required": req}
    if opts:
        d["options"] = opts
    if ph:
        d["placeholder"] = ph
    return d


MODULES = [
    ("m-personal", "Personal assistance", "Errands, household help, reservations, special requests"),
    ("m-home", "Home & property", "Repairs, maintenance, real estate, shifting, security installation"),
    ("m-mobility", "Mobility & lifestyle", "Travel, transport, vehicle services, events, dining"),
    ("m-professional", "Professional assistance", "Legal, financial, health, education, documentation, business support"),
    ("m-jobs", "Quick Jobs & staffing", "Drivers, shopkeepers, helpers, sales, reception, temporary staff"),
    ("m-commerce", "Commerce & sourcing", "Product sourcing, shopping, delivery and logistics"),
]

# code, name, description, stages, SLA hours per status, label overrides
WORKFLOWS = [
    ("home_service", "Home service", "Request, provider match, quote, customer approval, booking, fulfilment",
     ["Request", "Provider match", "Quote", "Customer approval", "Booking", "Fulfilment"], {}),
    ("professional_referral", "Professional referral", "Enquiry, scope check, qualified professional, appointment, outcome",
     ["Enquiry", "Scope check", "Qualified professional", "Appointment", "Outcome"],
     {"under_review": "Scope check", "finding_providers": "Finding a qualified professional", "confirmed": "Appointment confirmed"}),
    ("product_sourcing", "Product sourcing", "Product brief, source search, options/quotes, approval, purchase/delivery",
     ["Product brief", "Source search", "Options & quotes", "Approval", "Purchase / delivery"],
     {"finding_providers": "Searching sources", "quotes_received": "Options received", "in_progress": "Purchase & delivery"}),
    ("quick_jobs", "Quick Jobs", "Vacancy, employer verification, publish, applications, shortlist, interview, hired/closed",
     ["Vacancy", "Employer verification", "Publish", "Applications", "Shortlist", "Interview", "Hired / closed"],
     {"under_review": "Employer verification", "finding_providers": "Publishing & applications"}),
    ("reservation", "Reservation", "Date/time/party details, availability check, customer approval, confirmation",
     ["Details", "Availability check", "Customer approval", "Confirmation"],
     {"finding_providers": "Checking availability", "confirmed": "Reservation confirmed"}),
    ("special_request", "Special request", "Manual review, agent clarification, suitable workflow selection",
     ["Manual review", "Agent clarification", "Workflow selection"], {"under_review": "Manual review"}),
]
SLA = {"submitted": 2, "under_review": 4, "finding_providers": 24, "quotes_received": 6, "awaiting_approval": 48,
       "confirmed": 36, "in_progress": 72, "completed": 24}

GENERIC_HOW = ["We confirm the details and what you need", "Your agent invites verified providers to quote",
               "You compare quotes and approve one - nothing is booked without your approval",
               "We coordinate until the job is done, then you rate it"]
GENERIC_PREPARE = ["Your location and a convenient time", "A short description of what you need",
                   "Photos or documents, if you have them", "Your budget range (optional)"]

HOME_STAGES = "home_service"

# slug, name, module, icon, colour, phase, active, workflow, description, keywords, subcategories, schema, includes, prepare
CATEGORIES = [
    ("real-estate", "Real estate & property", "m-home", "🏘️", "#E6F4D7", 5, 1, "professional_referral",
     "Rental, sale and property visits with verified agents.", "rent flat house buy sell property apartment lease visit broker",
     ["Rent a home", "Buy a home", "Sell a property", "Schedule property visits"],
     [f("intent", "What do you want to do?", "select", True, ["Rent", "Buy", "Sell", "Lease out"]),
      f("property_type", "Property type", "select", True, ["Apartment", "Independent house", "Villa", "Plot", "Commercial"]),
      f("budget_note", "Budget / expected rent", "text", False, None, "e.g. Rs 25,000 per month")],
     ["Rental and resale listings", "Property visits", "Rental agreement help", "Price negotiation support"], None),
    ("home-repairs", "Home repairs & maintenance", "m-home", "🛠️", "#E3EEFB", 1, 1, "home_service",
     "Plumbing, electrical, AC and appliance repair from verified professionals.",
     "plumber plumbing tap leak leakage water electrician electrical wiring switch mcb fan ac air conditioner repair appliance fridge washing machine carpenter painter fix",
     ["Plumbing", "Electrician", "AC service & repair", "Appliance repair", "Carpentry", "Painting"],
     [f("issue_type", "What needs fixing?", "select", True, ["Plumbing", "Electrical", "AC", "Appliance", "Carpentry", "Painting", "Other"]),
      f("property_type", "Property type", "select", True, ["Apartment", "Independent house", "Office", "Shop"]),
      f("access_notes", "Access notes (gate code, floor, pets)", "text", False)],
     ["Plumbing - leaks, taps, fittings", "Electrical - wiring, switches, fans", "AC service, gas refill and repair", "Appliance repair and installation"], None),
    ("home-shifting", "Home shifting & setup", "m-home", "📦", "#FFF1D6", 2, 1, "home_service",
     "Packers, movers and installation for a stress-free move.", "shifting moving packers movers relocate transport furniture setup install",
     ["Packing & moving", "Local shifting", "Furniture assembly", "Appliance installation"],
     [f("from_locality", "Moving from", "text", True), f("to_locality", "Moving to", "text", True),
      f("home_size", "Home size", "select", True, ["1 BHK", "2 BHK", "3 BHK", "4 BHK+", "Office"]),
      f("packing", "Service", "select", False, ["Packing + moving", "Moving only"])],
     ["Packing with quality materials", "Loading, transport and unloading", "Furniture dismantling & assembly", "Basic appliance set-up"], None),
    ("daily-errands", "Daily errands & shopping", "m-personal", "🛍️", "#FBE4E1", 1, 1, "home_service",
     "Pickups, purchases and local errands handled for you.", "errand pickup purchase queue buy groceries courier paperwork local",
     ["Pickup & drop", "Local purchase", "Queue / paperwork", "Gifting"],
     [f("task_type", "Type of errand", "select", True, ["Pickup", "Purchase", "Queue / paperwork", "Other"]),
      f("items", "What should we get or do?", "textarea", True), f("deliver_by", "Needed by", "date", False)],
     ["Pickup and delivery within the city", "Buying items you list", "Standing in queues", "Document drop-offs"], None),
    ("travel-transport", "Travel & transport", "m-mobility", "🚗", "#E3EEFB", 1, 1, "reservation",
     "Local travel, drivers and transport coordination.", "driver car cab taxi airport drop pickup travel outstation transport tempo traveller chauffeur",
     ["Airport drop / pickup", "Drivers", "Outstation trips", "Daily commute", "Tempo traveller"],
     [f("trip_type", "Trip type", "select", True, ["Airport drop", "Airport pickup", "Outstation", "Local hourly", "Daily driver"]),
      f("pickup_point", "Pickup point", "text", True), f("drop_point", "Drop point", "text", False),
      f("passengers", "Passengers", "number", False), f("vehicle_pref", "Vehicle preference", "select", False, ["Hatchback", "Sedan", "SUV", "Tempo traveller"])],
     ["Verified drivers and fleets", "Airport and railway transfers", "Outstation round trips", "Hourly packages"], None),
    ("tickets-reservations", "Tickets & reservations", "m-personal", "🎟️", "#F0E7FB", 8, 0, "reservation",
     "Travel, event and appointment reservations.", "ticket booking train flight movie reservation appointment event pass",
     ["Travel tickets", "Event tickets", "Appointments"], [f("details", "What should we book?", "textarea", True)],
     ["Travel and event tickets", "Appointment booking", "Itinerary coordination"], None),
    ("events-celebrations", "Events & celebrations", "m-mobility", "🎉", "#FBE4E1", 5, 1, "home_service",
     "Event planning, vendors and staffing.", "event party birthday celebration decor decoration anniversary corporate festival planner dj",
     ["Birthday party", "Corporate event", "Anniversary", "Baby shower", "Festival decor"],
     [f("event_type", "Type of event", "select", True, ["Birthday", "Corporate", "Anniversary", "Baby shower", "Festival", "Other"]),
      f("guests", "Expected guests", "number", True), f("venue_status", "Venue", "select", False, ["Venue booked", "Need venue help"])],
     ["Event planners and decorators", "Catering, music and entertainment", "Event-day staffing", "Vendor coordination"], None),
    ("dining-entertainment", "Dining & entertainment", "m-mobility", "🍽️", "#FFF1D6", 8, 0, "reservation",
     "Dining and entertainment coordination.", "dining restaurant table reservation entertainment show outing",
     ["Table reservation", "Entertainment plans"], [f("details", "What do you have in mind?", "textarea", True)],
     ["Table reservations", "Outing planning"], None),
    ("vehicle-services", "Vehicle services", "m-mobility", "🔧", "#E3EEFB", 2, 1, "home_service",
     "Repairs, servicing and cleaning for your vehicle.", "car bike scooter vehicle service repair cleaning wash mechanic towing puncture battery",
     ["Car service", "Bike service", "Car cleaning", "Towing & breakdown"],
     [f("vehicle_type", "Vehicle", "select", True, ["Car", "Bike", "Auto", "Commercial"]),
      f("service_needed", "Service needed", "select", True, ["Service", "Repair", "Cleaning", "Towing"]),
      f("registration_no", "Registration number", "text", False)],
     ["Doorstep servicing", "Pickup and drop for repairs", "Detailing and cleaning", "Roadside help"], None),
    ("health-wellness", "Health & wellness", "m-professional", "💚", "#E6F4D7", 8, 0, "professional_referral",
     "Appointments and professional referrals. BUZZER does not give medical advice.", "doctor clinic health wellness physio appointment nurse diagnostic",
     ["Doctor appointment", "Physiotherapy", "Home nursing"], [f("details", "What do you need?", "textarea", True)],
     ["Appointment coordination", "Referrals to qualified professionals"], None),
    ("beauty-personal-care", "Beauty & personal care", "m-mobility", "💇", "#FBE4E1", 8, 0, "home_service",
     "Salon, grooming and home services.", "salon beauty grooming haircut facial spa makeup mehendi",
     ["Salon at home", "Grooming", "Bridal makeup"], [f("details", "What service do you want?", "textarea", True)],
     ["Salon and grooming at home", "Bridal and party makeup"], None),
    ("education-career", "Education & career", "m-professional", "🎓", "#F0E7FB", 8, 0, "professional_referral",
     "Training, admissions and career support.", "tuition tutor coaching admission career training course exam",
     ["Home tutor", "Coaching", "Career counselling"], [f("details", "What do you need?", "textarea", True)],
     ["Tutors and trainers", "Admission support"], None),
    ("professional-documentation", "Professional & documentation", "m-professional", "📑", "#E3EEFB", 8, 0, "professional_referral",
     "CA, documentation and professional referrals.", "ca accountant tax gst documentation notary registration passport visa",
     ["CA / tax", "Documentation", "Notary"], [f("details", "What do you need?", "textarea", True)],
     ["CA and tax referrals", "Documentation support"], None),
    ("household-family", "Household & family assistance", "m-personal", "🧹", "#E6F4D7", 1, 1, "home_service",
     "Cleaning, cooks, caregivers and household support.", "cleaning maid cook babysitter nanny elder care laundry housekeeping deep clean sofa bathroom kitchen",
     ["Home cleaning", "Deep cleaning", "Cook", "Babysitter", "Elder care"],
     [f("help_type", "Type of help", "select", True, ["Cleaning", "Cook", "Babysitter", "Elder care", "Laundry", "Other"]),
      f("hours_per_day", "Hours per day", "number", False), f("days_per_week", "Days per week", "number", False)],
     ["Verified house-help and caregivers", "One-time or regular schedules", "Deep-cleaning teams", "Replacement support"], None),
    ("matrimony-social", "Matrimony & social", "m-professional", "💍", "#FBE4E1", 8, 0, "professional_referral",
     "Matrimony and social introductions.", "matrimony marriage match social introduction",
     ["Matrimony help"], [f("details", "Tell us what you are looking for", "textarea", True)], ["Introductions", "Profile support"], None),
    ("business-office", "Business & office assistance", "m-professional", "🏢", "#E3EEFB", 8, 0, "home_service",
     "Office support and business services.", "office business support admin virtual assistant stationery",
     ["Office support", "Admin help"], [f("details", "What do you need?", "textarea", True)], ["Office assistants", "Admin support"], None),
    ("delivery-logistics", "Delivery & logistics", "m-commerce", "🚚", "#FFF1D6", 2, 1, "home_service",
     "Pickup, delivery and transport.", "delivery courier parcel logistics pickup drop transport goods",
     ["Same-day delivery", "Parcel pickup", "Goods transport"],
     [f("item_type", "What are we delivering?", "text", True), f("pickup_point", "Pickup address / area", "text", True),
      f("drop_point", "Drop address / area", "text", True), f("weight", "Approximate weight", "select", False, ["Under 5 kg", "5-20 kg", "20-100 kg", "Over 100 kg"])],
     ["Same-day city delivery", "Pickup and drop", "Goods transport"], None),
    ("special-requests", "Special requests", "m-personal", "✨", "#F0E7FB", 1, 1, "special_request",
     "Anything else - manually reviewed by your agent.", "special other custom unusual request help anything",
     ["Something else"], [f("contact_pref", "Best way to reach you", "select", False, ["Call", "WhatsApp"])],
     ["Manual review by your agent", "Custom sourcing of providers", "Clarification before we proceed"], None),
    ("financial-services", "Financial services assistance", "m-professional", "🏦", "#E6F4D7", 8, 0, "professional_referral",
     "Banking, insurance and loan enquiry coordination. BUZZER does not give financial advice.", "bank insurance loan finance enquiry mutual fund",
     ["Insurance enquiry", "Loan enquiry"], [f("details", "What do you need?", "textarea", True)], ["Enquiry coordination", "Referrals"], None),
    ("security-safety", "Security & safety services", "m-home", "🛡️", "#E3EEFB", 8, 0, "home_service",
     "Guards, CCTV and safety installation.", "security guard cctv camera alarm safety installation",
     ["CCTV installation", "Security guards"], [f("details", "What do you need?", "textarea", True)], ["CCTV installation", "Guards"], None),
    ("shopping-sourcing", "Shopping & product sourcing", "m-commerce", "🛒", "#FBE4E1", 5, 1, "product_sourcing",
     "Product discovery and sourcing.", "shopping buy source product find laptop furniture gadget compare price",
     ["Find a product", "Bulk purchase", "Gifts"],
     [f("product", "What are you looking for?", "text", True), f("quantity", "Quantity", "number", False), f("brand_pref", "Brand preference", "text", False)],
     ["Finding the best options", "Price comparison", "Delivery to your door"], None),
    ("legal-services", "Legal services assistance", "m-professional", "⚖️", "#F0E7FB", 8, 0, "professional_referral",
     "Qualified lawyer and legal professional referrals. BUZZER does not give legal advice.", "legal lawyer advocate notary agreement court dispute",
     ["Lawyer referral", "Agreement drafting"], [f("details", "What do you need?", "textarea", True)], ["Referrals to qualified lawyers"], None),
    ("quick-jobs-staffing", "Quick jobs & staffing", "m-jobs", "💼", "#FFF1D6", 6, 1, "quick_jobs",
     "Hire drivers, shopkeepers, helpers, sales and temporary staff - or find a job.", "job jobs hire staff driver helper sales reception shopkeeper vacancy work employment",
     ["Hire staff", "Find a job"],
     [f("role_needed", "Role needed", "text", True), f("headcount", "How many people?", "number", True), f("duration", "For how long?", "text", False)],
     ["Verified employers", "Candidate shortlisting", "Interview coordination"], None),
    ("wedding-catering", "Wedding & catering", "m-mobility", "🍛", "#FFF1D6", 7, 1, "home_service",
     "Wedding planning, venues, decorators, catering teams and event-day coordination.", "wedding catering marriage reception venue decorator menu mandap sangeet engagement",
     ["Full wedding planning", "Catering only", "Decor & mandap", "Venue search"], [],
     ["Wedding planners and venue coordination", "Catering teams and menu planning", "Decor and mandap vendors", "Event-day coordination"], None),
    ("photography-videography", "Photography & videography", "m-mobility", "📸", "#E3EEFB", 7, 1, "home_service",
     "Wedding and event photography, candid coverage, pre-wedding shoots, editing and albums.", "photo photography photographer video videography candid prewedding album shoot drone",
     ["Wedding photography", "Candid coverage", "Pre-wedding shoot", "Event videography"], [],
     ["Photographers and videographers", "Candid and traditional coverage", "Editing and albums", "Package comparison"], None),
]

SETTINGS = [
    ("agent_default_capacity", "40", "Default agent capacity (active requests) - configurable, validated with pilot data", "allocation"),
    ("complaint_sla_hours", "24", "Complaint first-response SLA (hours)", "operations"),
    ("quote_validity_days", "7", "Default quote validity (days)", "operations"),
    ("otp_expiry_minutes", "5", "OTP validity (minutes)", "security"),
    ("session_hours_staff", "8", "Staff session length (hours)", "security"),
    ("maintenance_mode", "off", "Maintenance mode", "operations"),
]

INTEGRATIONS = [
    ("SMS gateway", "sms", "sandbox", "OTP and alerts. Development uses a fake adapter - the OTP is returned in the API response."),
    ("WhatsApp Business API", "whatsapp", "sandbox", "Customer-agent messages with approved templates and consent. Simulated."),
    ("Payment gateway", "payments", "sandbox", "Sandbox adapter. Activate only after legal, tax and gateway review."),
    ("Object storage", "storage", "connected", "Local private storage with short-lived signed download links."),
    ("Maps & geocoding", "maps", "disconnected", "Not configured. Locality is captured as text."),
    ("Email (transactional)", "email", "sandbox", "Receipts and notices. Simulated."),
    ("Error tracking", "observability", "degraded", "Structured logs only; connect an error tracker before production."),
]

ASSIGNMENT_RULES = [
    ("Dedicated primary agent", "primary_default", "Future requests route to the customer's primary agent when active and within capacity.", {"respect_capacity": True}, 1),
    ("New customer allocation", "new_customer", "New customers are linked to the least-loaded available pool agent.", {"strategy": "least_loaded"}, 2),
    ("Overflow to backup agent", "overload", "When the primary agent is at capacity or unavailable, route to the backup agent.", {"fallback": "backup"}, 3),
    ("Agent absence cover", "absence", "When the primary agent is away or offline, route to an available pool agent.", {"fallback": "pool"}, 4),
    ("Specialist routing", "specialist", "Events, weddings and legal-adjacent requests may route to a specialist agent.", {"categories": ["wedding-catering", "photography-videography"]}, 5),
]

PLANS = [
    ("free", "Basic", 0, 0, ["Request any service", "Compare quotes before you approve", "Chat with your agent"], 0),
    ("plus", "BUZZER Plus", 499, 4790, ["Dedicated Personal Agent", "Priority Support & Faster Response", "Exclusive Partner Discounts",
                                       "Track & Manage All Requests", "Cancel Anytime"], 1),
]

HELP = [
    ("How does BUZZER work?", "General", "customer", "You tell us what you need. Your dedicated BUZZER agent finds verified providers, collects quotes and shares the best options. You approve a quote - only then is anything booked."),
    ("Is anything booked without my approval?", "Quotes & booking", "customer", "Never. Your agent shares quotes with you and a booking is created only after you explicitly approve one. If a price changes after approval, we ask for your consent again."),
    ("How do payments work?", "Payments", "customer", "You may pay a deposit to secure the slot and the balance after the work is done. Payments are held by BUZZER and released to the provider after you confirm completion. (Sandbox in this build.)"),
    ("Can I cancel a booking?", "Quotes & booking", "customer", "Yes. Free cancellation before the provider starts. If you've paid, a refund request is raised automatically and reviewed by our finance team within 3-5 working days."),
    ("What does 'Verified' mean?", "Trust & safety", "all", "A verified provider has submitted ID and address proof that our team has reviewed. Unverified providers are never shown as verified."),
    ("How do I raise a complaint?", "Support", "customer", "Open Support from your profile, choose the request, describe the issue and attach photos. You can track every update until it is resolved."),
    ("How do I get paid as a partner?", "Payouts", "partner", "Earnings are credited after the customer confirms completion, net of BUZZER commission. Request a payout from Earnings once your business is verified and you've added a bank account or UPI ID."),
    ("How do I become a verified partner?", "Verification", "partner", "Upload ID proof and business address proof, then submit for verification. Our team reviews documents within 2 working days."),
    ("Is there a fee to apply for jobs?", "Quick Jobs", "customer", "No. BUZZER never charges candidates a recruitment fee. Report any vacancy that asks you for money."),
    ("How is my data protected?", "Privacy", "all", "Your data is visible only to people who need it for your request. You can export or request deletion of your data from Profile > Privacy."),
]

REVIEW_COMMENTS = [
    "Quick response and professional work. Highly recommended.", "On time and fair price. Will use again.",
    "Very polite and neat finish. Good value.", "Solved the problem in one visit. Thank you!",
    "Good work, slight delay at the start.", "Clear quote, no surprises. Great service.",
    "Friendly and careful with the house.", "Excellent. The agent kept me updated throughout.",
]
