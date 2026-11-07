-- Reference data: launch city (Hyderabad) and the initial dynamic catalogue.
-- All of this is ordinary configuration data, editable from the Admin panel.
-- New cities/categories/services are added through the Admin APIs; no code deploy required.

INSERT INTO cities(code, name, state) VALUES ('HYD', 'Hyderabad', 'Telangana');

INSERT INTO localities(city_id, name, pincode)
SELECT c.id, l.name, l.pincode FROM cities c,
 (VALUES ('Kondapur','500084'),('Madhapur','500081'),('Gachibowli','500032'),('Kukatpally','500072'),
         ('Hitech City','500081'),('Banjara Hills','500034'),('Jubilee Hills','500033'),('Ameerpet','500016'),
         ('Begumpet','500016'),('Secunderabad','500003'),('Miyapur','500049'),('LB Nagar','500074'),
         ('Dilsukhnagar','500060'),('Manikonda','500089'),('Kompally','500014'),('Uppal','500039')) AS l(name, pincode)
WHERE c.code = 'HYD';

-- ---------------------------------------------------------------------------
-- Categories (25)
-- ---------------------------------------------------------------------------
INSERT INTO categories(code, name, description, icon, sort_order, workflow_template, is_global_special, advisory_notice, default_form_schema) VALUES
('REAL_ESTATE','Real Estate & Property','Rent, buy, sell and lease residential or commercial property','home-city',1,'PROPERTY_REQUIREMENT',FALSE,NULL,NULL),
('HOME_REPAIRS','Home Repairs & Maintenance','Plumbing, electrical, AC, carpentry, painting and more','tools',2,'MENU_DRIVEN',FALSE,NULL,NULL),
('HOME_SHIFTING','Home Shifting & Setup','Packers and movers, setup and installation','truck',3,'MENU_DRIVEN',FALSE,NULL,NULL),
('DAILY_ERRANDS','Daily Errands & Shopping','Pickups, groceries, bill submissions and errands','shopping-bag',4,'MENU_DRIVEN',FALSE,NULL,NULL),
('TRAVEL_TRANSPORT','Travel & Transport','Cabs, outstation trips, drivers and tour planning','car',5,'MENU_DRIVEN',FALSE,NULL,NULL),
('TICKETS','Tickets & Reservations','Bus, train, flight, movie and event tickets','ticket',6,'MENU_DRIVEN',FALSE,NULL,NULL),
('EVENTS','Events & Celebrations','Birthdays, corporate events, decorations and planning','party',7,'EVENT_BRIEF',FALSE,NULL,NULL),
('DINING','Dining & Entertainment','Table reservations, private dining and entertainment','dining',8,'MENU_DRIVEN',FALSE,NULL,NULL),
('VEHICLE','Vehicle Services','Car and bike service, cleaning, documentation','wrench',9,'MENU_DRIVEN',FALSE,NULL,NULL),
('HEALTH','Health & Wellness','Appointments with qualified doctors, diagnostics and wellness providers','heart',10,'MENU_DRIVEN',FALSE,'BUZZER coordinates appointments with qualified providers. BUZZER does not diagnose or treat any condition.',NULL),
('BEAUTY','Beauty & Personal Care','Salon at home, grooming and spa','sparkle',11,'MENU_DRIVEN',FALSE,NULL,NULL),
('EDUCATION','Education & Career','Tutors, coaching, admissions and career guidance','school',12,'MENU_DRIVEN',FALSE,NULL,NULL),
('DOCUMENTATION','Professional & Documentation','Certificates, registrations, applications and paperwork','file',13,'MENU_DRIVEN',FALSE,NULL,NULL),
('HOUSEHOLD','Household & Family Assistance','Maids, cooks, babysitters, elder care','family',14,'MENU_DRIVEN',FALSE,NULL,NULL),
('MATRIMONY','Matrimony & Social','Matchmaking coordination and social services','rings',15,'MENU_DRIVEN',FALSE,NULL,NULL),
('BUSINESS','Business & Office Assistance','Office setup, admin support and business services','briefcase',16,'MENU_DRIVEN',FALSE,NULL,NULL),
('DELIVERY','Delivery & Logistics','Parcel pickup and drop, courier and logistics','package',17,'MENU_DRIVEN',FALSE,NULL,NULL),
('SPECIAL_REQUESTS','Special Requests','Anything else - tell us and your agent will arrange it','star',18,'GLOBAL_SPECIAL_REQUEST',TRUE,NULL,NULL),
('FINANCIAL','Financial Services Assistance','Appointments with authorised financial service providers','bank',19,'MENU_DRIVEN',FALSE,'BUZZER only arranges appointments with authorised providers. BUZZER does not provide financial advice or act as a regulated intermediary.',NULL),
('SECURITY','Security & Safety Services','Security guards, CCTV installation and safety audits','shield',20,'MENU_DRIVEN',FALSE,'Security providers are verified for relevant licences and permissions before onboarding.',NULL),
('PRODUCT_SOURCING','Shopping & Product Sourcing','Find, compare and source products','cart',21,'MENU_DRIVEN',FALSE,NULL,NULL),
('LEGAL','Legal Services Assistance','Referrals and appointments with qualified lawyers','gavel',22,'MENU_DRIVEN',FALSE,'BUZZER coordinates referrals to qualified lawyers. BUZZER does not provide legal advice.',NULL),
('QUICK_JOBS','Quick Jobs & Staffing','Hire drivers, helpers, staff - or find a job','id-card',23,'JOB_RECRUITMENT',FALSE,NULL,NULL),
('WEDDING_CATERING','Wedding & Catering','Venues, catering, decoration and day coordination','cake',24,'EVENT_BRIEF',FALSE,NULL,NULL),
('PHOTOGRAPHY','Photography & Videography','Photographers and videographers for every occasion','camera',25,'MENU_DRIVEN',FALSE,NULL,NULL);

-- Generic default form used when a service item does not define its own form.
UPDATE categories SET default_form_schema = '{
  "fields": [
    {"key":"details","label":"Describe your requirement","type":"textarea","required":true,"minLength":10,"maxLength":2000},
    {"key":"urgency","label":"How soon do you need it?","type":"select","required":true,"options":["Today","Within 2-3 days","This week","Flexible"]},
    {"key":"photos","label":"Photos (optional)","type":"photo","required":false,"maxItems":5}
  ]}'::jsonb;

UPDATE categories SET default_form_schema = '{
  "fields": [
    {"key":"event_type","label":"Event type","type":"select","required":true,"options":["Birthday","Anniversary","Corporate","Wedding","Engagement","Baby shower","Other"]},
    {"key":"event_date","label":"Event date","type":"date","required":true},
    {"key":"guest_count","label":"Approximate guests","type":"number","required":true,"min":1,"max":10000},
    {"key":"budget","label":"Budget (INR)","type":"number","required":false,"min":0},
    {"key":"venue_needed","label":"Do you need a venue?","type":"boolean","required":false},
    {"key":"services_needed","label":"Services needed","type":"multiselect","required":true,"options":["Venue","Decoration","Catering","Photography","Music / DJ","Day coordination","Invitations"]},
    {"key":"details","label":"Tell us more","type":"textarea","required":false,"maxLength":2000}
  ]}'::jsonb
WHERE code IN ('EVENTS','WEDDING_CATERING');

-- All categories are available in Hyderabad at launch.
INSERT INTO category_cities(category_id, city_id)
SELECT c.id, ci.id FROM categories c CROSS JOIN cities ci WHERE ci.code = 'HYD';

-- ---------------------------------------------------------------------------
-- Subcategories
-- ---------------------------------------------------------------------------
INSERT INTO subcategories(category_id, code, name, icon, sort_order)
SELECT c.id, s.code, s.name, s.icon, s.ord FROM categories c JOIN (VALUES
 ('REAL_ESTATE','RENT','Rent / Lease','key',1),
 ('REAL_ESTATE','BUY','Buy Property','home',2),
 ('REAL_ESTATE','SELL','Sell Property','tag',3),
 ('HOME_REPAIRS','PLUMBING','Plumbing','pipe',1),
 ('HOME_REPAIRS','ELECTRICAL','Electrical','bolt',2),
 ('HOME_REPAIRS','AC','AC Service & Repair','snow',3),
 ('HOME_REPAIRS','CARPENTRY','Carpentry','hammer',4),
 ('HOME_REPAIRS','PAINTING','Painting','brush',5),
 ('HOME_REPAIRS','APPLIANCE','Appliance Repair','plug',6),
 ('HOME_REPAIRS','PEST_CONTROL','Pest Control','bug',7),
 ('HOME_SHIFTING','PACKERS_MOVERS','Packers & Movers','truck',1),
 ('HOME_SHIFTING','HOME_SETUP','Home Setup & Installation','sofa',2),
 ('DAILY_ERRANDS','GROCERY','Grocery & Essentials','basket',1),
 ('DAILY_ERRANDS','PICKUP_DROP','Pickup & Drop Errands','route',2),
 ('TRAVEL_TRANSPORT','CABS','Cabs & Outstation','car',1),
 ('TRAVEL_TRANSPORT','DRIVER_ON_DEMAND','Driver on Demand','steering',2),
 ('TRAVEL_TRANSPORT','TOUR_PLANNING','Tour Planning','map',3),
 ('TICKETS','TRAVEL_TICKETS','Bus / Train / Flight','ticket',1),
 ('TICKETS','ENTERTAINMENT_TICKETS','Movies & Events','film',2),
 ('EVENTS','BIRTHDAY','Birthday Parties','cake',1),
 ('EVENTS','CORPORATE','Corporate Events','briefcase',2),
 ('EVENTS','DECORATION','Decoration','balloon',3),
 ('DINING','RESERVATIONS','Table Reservations','dining',1),
 ('DINING','PRIVATE_DINING','Private Chef & Dining','chef',2),
 ('VEHICLE','CAR_SERVICE','Car Service','car',1),
 ('VEHICLE','BIKE_SERVICE','Bike Service','bike',2),
 ('VEHICLE','VEHICLE_CLEANING','Cleaning & Detailing','spray',3),
 ('HEALTH','DOCTOR_APPOINTMENT','Doctor Appointments','stethoscope',1),
 ('HEALTH','DIAGNOSTICS','Diagnostics & Lab Tests','flask',2),
 ('HEALTH','FITNESS','Fitness & Yoga','fitness',3),
 ('BEAUTY','SALON_AT_HOME','Salon at Home','scissors',1),
 ('BEAUTY','SPA','Spa & Massage','spa',2),
 ('EDUCATION','TUTORS','Home Tutors','book',1),
 ('EDUCATION','ADMISSIONS','Admissions & Career','graduation',2),
 ('DOCUMENTATION','CERTIFICATES','Certificates & Registrations','file',1),
 ('DOCUMENTATION','PASSPORT_VISA','Passport & Visa Assistance','passport',2),
 ('HOUSEHOLD','MAIDS','Maids & Cleaning','broom',1),
 ('HOUSEHOLD','COOKS','Cooks','chef',2),
 ('HOUSEHOLD','ELDER_CARE','Elder & Child Care','family',3),
 ('MATRIMONY','MATCHMAKING','Matchmaking Coordination','rings',1),
 ('BUSINESS','OFFICE_SETUP','Office Setup','building',1),
 ('BUSINESS','ADMIN_SUPPORT','Admin & Back Office','clipboard',2),
 ('DELIVERY','PARCEL','Parcel Pickup & Drop','package',1),
 ('DELIVERY','COURIER','Courier','send',2),
 ('SPECIAL_REQUESTS','ANYTHING_ELSE','Anything Else','star',1),
 ('FINANCIAL','LOANS_INSURANCE','Loan & Insurance Appointments','bank',1),
 ('FINANCIAL','TAX_FILING','Tax Filing Appointments','calculator',2),
 ('SECURITY','GUARDS','Security Guards','shield',1),
 ('SECURITY','CCTV','CCTV Installation','camera',2),
 ('PRODUCT_SOURCING','SOURCING','Product Sourcing','search',1),
 ('LEGAL','LAWYER_REFERRAL','Lawyer Referral','gavel',1),
 ('LEGAL','DOCUMENT_DRAFTING','Document Drafting Appointments','file',2),
 ('QUICK_JOBS','HIRE_STAFF','Hire Staff','users',1),
 ('QUICK_JOBS','FIND_JOB','Find a Job','id-card',2),
 ('WEDDING_CATERING','CATERING','Catering','dish',1),
 ('WEDDING_CATERING','VENUES','Venues','building',2),
 ('WEDDING_CATERING','WEDDING_PLANNING','Wedding Planning','rings',3),
 ('PHOTOGRAPHY','EVENT_PHOTOGRAPHY','Event Photography','camera',1),
 ('PHOTOGRAPHY','VIDEOGRAPHY','Videography','video',2),
 ('PHOTOGRAPHY','PORTRAIT','Portrait & Product Shoots','image',3)
) AS s(cat, code, name, icon, ord) ON c.code = s.cat;

-- ---------------------------------------------------------------------------
-- Service items (menus). Each subcategory also gets an "Other ... Requirement"
-- item so category-specific special requests stay inside their category.
-- ---------------------------------------------------------------------------
INSERT INTO service_items(subcategory_id, code, name, pricing_type, base_price, min_price, max_price, unit, sort_order)
SELECT sc.id, i.code, i.name, i.ptype, i.base, i.minp, i.maxp, i.unit, i.ord
FROM subcategories sc JOIN categories c ON c.id = sc.category_id
JOIN (VALUES
 ('HOME_REPAIRS','PLUMBING','TAP_REPAIR','Tap Repair','STARTING_FROM',199,NULL,NULL,'per tap',1),
 ('HOME_REPAIRS','PLUMBING','PIPE_LEAKAGE','Pipe Leakage','VISIT_PLUS_LABOUR',149,NULL,NULL,'visit',2),
 ('HOME_REPAIRS','PLUMBING','BATHROOM_FITTINGS','Bathroom Fittings','STARTING_FROM',299,NULL,NULL,'per fitting',3),
 ('HOME_REPAIRS','PLUMBING','PIPELINE_INSTALLATION','Pipeline Installation','QUOTE_REQUIRED',NULL,NULL,NULL,NULL,4),
 ('HOME_REPAIRS','ELECTRICAL','SWITCH_SOCKET','Switch & Socket Fixing','STARTING_FROM',99,NULL,NULL,'per point',1),
 ('HOME_REPAIRS','ELECTRICAL','WIRING','Wiring & Rewiring','RANGE',NULL,499,5000,NULL,2),
 ('HOME_REPAIRS','ELECTRICAL','FAN_INSTALLATION','Fan Installation','FIXED',249,NULL,NULL,'per fan',3),
 ('HOME_REPAIRS','ELECTRICAL','LIGHT_INSTALLATION','Light Installation','STARTING_FROM',149,NULL,NULL,'per light',4),
 ('HOME_REPAIRS','ELECTRICAL','INVERTER_SETUP','Inverter Setup','STARTING_FROM',799,NULL,NULL,NULL,5),
 ('HOME_REPAIRS','AC','AC_INSTALLATION','AC Installation','STARTING_FROM',1499,NULL,NULL,'per unit',1),
 ('HOME_REPAIRS','AC','AC_REPAIR','AC Repair','VISIT_PLUS_LABOUR',299,NULL,NULL,'visit',2),
 ('HOME_REPAIRS','AC','AC_UNINSTALLATION','AC Uninstallation','FIXED',699,NULL,NULL,'per unit',3),
 ('HOME_REPAIRS','AC','GAS_REFILL','Gas Refill','STARTING_FROM',2499,NULL,NULL,NULL,4),
 ('HOME_REPAIRS','AC','AC_AMC','Annual Maintenance (AMC)','PACKAGE',3999,NULL,NULL,'per year',5),
 ('HOME_REPAIRS','CARPENTRY','FURNITURE_REPAIR','Furniture Repair','QUOTE_REQUIRED',NULL,NULL,NULL,NULL,1),
 ('HOME_REPAIRS','CARPENTRY','DOOR_LOCK','Door & Lock Repair','STARTING_FROM',249,NULL,NULL,NULL,2),
 ('HOME_REPAIRS','PAINTING','INTERIOR_PAINTING','Interior Painting','PER_UNIT',18,NULL,NULL,'per sq ft',1),
 ('HOME_REPAIRS','PAINTING','EXTERIOR_PAINTING','Exterior Painting','QUOTE_REQUIRED',NULL,NULL,NULL,NULL,2),
 ('HOME_REPAIRS','APPLIANCE','WASHING_MACHINE','Washing Machine Repair','VISIT_PLUS_LABOUR',249,NULL,NULL,'visit',1),
 ('HOME_REPAIRS','APPLIANCE','REFRIGERATOR','Refrigerator Repair','VISIT_PLUS_LABOUR',249,NULL,NULL,'visit',2),
 ('HOME_REPAIRS','PEST_CONTROL','GENERAL_PEST','General Pest Control','STARTING_FROM',999,NULL,NULL,NULL,1),
 ('HOME_SHIFTING','PACKERS_MOVERS','LOCAL_SHIFTING','Local House Shifting','QUOTE_REQUIRED',NULL,NULL,NULL,NULL,1),
 ('HOME_SHIFTING','PACKERS_MOVERS','INTERCITY_SHIFTING','Intercity Shifting','QUOTE_REQUIRED',NULL,NULL,NULL,NULL,2),
 ('HOME_SHIFTING','HOME_SETUP','TV_MOUNTING','TV Wall Mounting','FIXED',499,NULL,NULL,NULL,1),
 ('DAILY_ERRANDS','GROCERY','GROCERY_RUN','Grocery Run','QUOTE_REQUIRED',NULL,NULL,NULL,NULL,1),
 ('DAILY_ERRANDS','PICKUP_DROP','DOCUMENT_PICKUP','Document Pickup & Drop','STARTING_FROM',149,NULL,NULL,NULL,1),
 ('TRAVEL_TRANSPORT','CABS','AIRPORT_TRANSFER','Airport Transfer','QUOTE_REQUIRED',NULL,NULL,NULL,NULL,1),
 ('TRAVEL_TRANSPORT','CABS','OUTSTATION','Outstation Trip','QUOTE_REQUIRED',NULL,NULL,NULL,NULL,2),
 ('TRAVEL_TRANSPORT','DRIVER_ON_DEMAND','HOURLY_DRIVER','Hourly Driver','HOURLY',199,NULL,NULL,'per hour',1),
 ('TICKETS','TRAVEL_TICKETS','BUS_TICKET','Bus Ticket','QUOTE_REQUIRED',NULL,NULL,NULL,NULL,1),
 ('TICKETS','TRAVEL_TICKETS','TRAIN_TICKET','Train Ticket','QUOTE_REQUIRED',NULL,NULL,NULL,NULL,2),
 ('TICKETS','TRAVEL_TICKETS','FLIGHT_TICKET','Flight Ticket','QUOTE_REQUIRED',NULL,NULL,NULL,NULL,3),
 ('EVENTS','BIRTHDAY','BIRTHDAY_PACKAGE','Birthday Party Package','PACKAGE',NULL,NULL,NULL,NULL,1),
 ('EVENTS','DECORATION','BALLOON_DECOR','Balloon Decoration','STARTING_FROM',1499,NULL,NULL,NULL,1),
 ('VEHICLE','CAR_SERVICE','CAR_GENERAL_SERVICE','Car General Service','STARTING_FROM',2499,NULL,NULL,NULL,1),
 ('VEHICLE','BIKE_SERVICE','BIKE_GENERAL_SERVICE','Bike General Service','STARTING_FROM',599,NULL,NULL,NULL,1),
 ('HEALTH','DOCTOR_APPOINTMENT','GP_APPOINTMENT','General Physician Appointment','QUOTE_REQUIRED',NULL,NULL,NULL,NULL,1),
 ('HEALTH','DIAGNOSTICS','HOME_SAMPLE','Home Sample Collection','QUOTE_REQUIRED',NULL,NULL,NULL,NULL,1),
 ('BEAUTY','SALON_AT_HOME','HAIRCUT_HOME','Haircut at Home','STARTING_FROM',299,NULL,NULL,NULL,1),
 ('EDUCATION','TUTORS','HOME_TUTOR','Home Tutor','HOURLY',400,NULL,NULL,'per hour',1),
 ('HOUSEHOLD','MAIDS','DEEP_CLEANING','Home Deep Cleaning','STARTING_FROM',2999,NULL,NULL,NULL,1),
 ('HOUSEHOLD','COOKS','DAILY_COOK','Daily Cook','QUOTE_REQUIRED',NULL,NULL,NULL,NULL,1),
 ('DELIVERY','PARCEL','SAME_DAY_PARCEL','Same-day Parcel','STARTING_FROM',99,NULL,NULL,NULL,1),
 ('SECURITY','CCTV','CCTV_INSTALL','CCTV Installation','QUOTE_REQUIRED',NULL,NULL,NULL,NULL,1),
 ('LEGAL','LAWYER_REFERRAL','LAWYER_CONSULT','Lawyer Consultation Appointment','QUOTE_REQUIRED',NULL,NULL,NULL,NULL,1),
 ('FINANCIAL','TAX_FILING','ITR_APPOINTMENT','Tax Filing Appointment (with authorised professional)','QUOTE_REQUIRED',NULL,NULL,NULL,NULL,1),
 ('WEDDING_CATERING','CATERING','WEDDING_CATERING','Wedding Catering','QUOTE_REQUIRED',NULL,NULL,NULL,NULL,1),
 ('PHOTOGRAPHY','EVENT_PHOTOGRAPHY','EVENT_SHOOT','Event Photo Shoot','QUOTE_REQUIRED',NULL,NULL,NULL,NULL,1),
 ('PHOTOGRAPHY','VIDEOGRAPHY','EVENT_VIDEO','Event Videography','QUOTE_REQUIRED',NULL,NULL,NULL,NULL,1)
) AS i(cat, sub, code, name, ptype, base, minp, maxp, unit, ord) ON c.code = i.cat AND sc.code = i.sub;

-- "Other <Subcategory> Requirement" for every subcategory (category-specific special request)
INSERT INTO service_items(subcategory_id, code, name, pricing_type, is_other, sort_order)
SELECT sc.id, 'OTHER_' || sc.code, 'Other ' || sc.name || ' Requirement', 'QUOTE_REQUIRED', TRUE, 999
FROM subcategories sc;

-- Plumbing-specific form (leakage etc.)
UPDATE service_items si SET form_schema = '{
  "fields": [
    {"key":"problem_area","label":"Where is the problem?","type":"select","required":true,"options":["Kitchen","Bathroom","Wash area","Overhead tank","Outside / Garden","Other"]},
    {"key":"leak_severity","label":"Severity","type":"select","required":true,"options":["Slow drip","Steady leak","Major leak / flooding"]},
    {"key":"pipe_type","label":"Pipe type (if known)","type":"select","required":false,"options":["PVC / CPVC","GI (metal)","Concealed","Not sure"]},
    {"key":"details","label":"Describe the issue","type":"textarea","required":true,"minLength":10,"maxLength":2000},
    {"key":"photos","label":"Photos of the problem","type":"photo","required":false,"maxItems":5}
  ]}'::jsonb
FROM subcategories sc WHERE si.subcategory_id = sc.id AND sc.code = 'PLUMBING' AND si.code IN ('PIPE_LEAKAGE','TAP_REPAIR');

-- Real-estate structured property requirement form
UPDATE service_items si SET form_schema = '{
  "fields": [
    {"key":"property_use","label":"Residential or Commercial","type":"select","required":true,"options":["Residential","Commercial"]},
    {"key":"property_type","label":"Property type","type":"select","required":true,"options":["Apartment","Independent house","Villa","Plot","Office","Shop","Warehouse"]},
    {"key":"bhk","label":"BHK","type":"select","required":false,"options":["1 RK","1 BHK","2 BHK","3 BHK","4 BHK","5+ BHK"]},
    {"key":"preferred_locations","label":"Preferred locations","type":"text","required":true,"maxLength":300},
    {"key":"budget_min","label":"Minimum budget (INR)","type":"number","required":true,"min":0},
    {"key":"budget_max","label":"Maximum budget (INR)","type":"number","required":true,"min":0},
    {"key":"furnishing","label":"Furnishing","type":"select","required":false,"options":["Unfurnished","Semi-furnished","Fully furnished","Any"]},
    {"key":"move_in_date","label":"Move-in / possession date","type":"date","required":false},
    {"key":"details","label":"Other requirements","type":"textarea","required":false,"maxLength":2000}
  ]}'::jsonb
FROM subcategories sc JOIN categories c ON c.id = sc.category_id
WHERE si.subcategory_id = sc.id AND c.code = 'REAL_ESTATE';

-- Real estate items (structured requirement per subcategory)
INSERT INTO service_items(subcategory_id, code, name, pricing_type, sort_order, form_schema)
SELECT sc.id, sc.code || '_REQUIREMENT', sc.name || ' Requirement', 'QUOTE_REQUIRED', 1,
 (SELECT form_schema FROM service_items x WHERE x.subcategory_id = sc.id AND x.is_other LIMIT 1)
FROM subcategories sc JOIN categories c ON c.id = sc.category_id WHERE c.code = 'REAL_ESTATE';

-- Pay-on-delivery not allowed by default for ticketing/financial/legal categories
UPDATE service_items si SET pay_on_delivery_allowed = FALSE
FROM subcategories sc JOIN categories c ON c.id = sc.category_id
WHERE si.subcategory_id = sc.id AND c.code IN ('TICKETS','FINANCIAL','LEGAL','REAL_ESTATE');

-- Illustrative commission rules (examples only, editable in Admin)
INSERT INTO commission_rules(scope_type, scope_id, rate_percent)
SELECT 'SUBCATEGORY', sc.id, r.rate FROM subcategories sc JOIN (VALUES ('PLUMBING',5.00),('ELECTRICAL',4.00),('AC',6.00)) AS r(code, rate) ON sc.code = r.code;
INSERT INTO commission_rules(scope_type, scope_id, rate_percent)
SELECT 'CATEGORY', c.id, r.rate FROM categories c JOIN (VALUES ('REAL_ESTATE',2.00),('EVENTS',5.00)) AS r(code, rate) ON c.code = r.code;

-- Versioned terms
INSERT INTO terms_versions(audience, version, title, summary) VALUES
('PARTNER','2026.1','BUZZER Partner Terms','Covers registration, subscription, commission, lead charges, service responsibility, cancellation, refunds, payouts and privacy. DRAFT - requires legal review before production use.'),
('CUSTOMER','2026.1','BUZZER Customer Terms','Covers service requests, quotations, payments, cancellations, refunds and privacy. DRAFT - requires legal review before production use.');
