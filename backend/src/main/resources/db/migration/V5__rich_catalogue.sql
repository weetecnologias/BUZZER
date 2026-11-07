-- Rich, menu-driven catalogue: priced items that customers add to a cart.
-- All names, prices and structure are illustrative starting values, editable by admins (no code deploy).
-- Real Estate keeps its structured requirement form; Quick Jobs and Special Requests keep their own flows.

CREATE FUNCTION pg_temp.sub(p_cat text, p_code text, p_name text, p_icon text, p_ord int) RETURNS void AS $$
  INSERT INTO subcategories(category_id, code, name, icon, sort_order)
  SELECT c.id, p_code, p_name, p_icon, p_ord FROM categories c WHERE c.code = p_cat
  ON CONFLICT (category_id, code) DO UPDATE SET name = EXCLUDED.name, icon = EXCLUDED.icon, sort_order = EXCLUDED.sort_order, active = TRUE;
$$ LANGUAGE sql;

CREATE FUNCTION pg_temp.item(p_cat text, p_sub text, p_code text, p_name text, p_type text, p_base numeric, p_unit text, p_ord int,
                             p_desc text DEFAULT NULL, p_duration text DEFAULT NULL) RETURNS void AS $$
  INSERT INTO service_items(subcategory_id, code, name, pricing_type, base_price, unit, sort_order, description, duration_label)
  SELECT s.id, p_code, p_name, p_type, p_base, p_unit, p_ord, p_desc, p_duration
  FROM subcategories s JOIN categories c ON c.id = s.category_id WHERE c.code = p_cat AND s.code = p_sub
  ON CONFLICT (subcategory_id, code) DO UPDATE SET name = EXCLUDED.name, pricing_type = EXCLUDED.pricing_type,
    base_price = EXCLUDED.base_price, unit = EXCLUDED.unit, sort_order = EXCLUDED.sort_order,
    description = COALESCE(EXCLUDED.description, service_items.description),
    duration_label = COALESCE(EXCLUDED.duration_label, service_items.duration_label), active = TRUE;
$$ LANGUAGE sql;

UPDATE categories SET workflow_template = 'MENU_DRIVEN' WHERE code IN ('EVENTS', 'WEDDING_CATERING');
UPDATE categories SET name = 'Wedding & Functions', description = 'Wedding, haldi, mehendi, sangeet, half saree functions, catering and venues' WHERE code = 'WEDDING_CATERING';
UPDATE categories SET description = 'Bus, train & flight tickets, cabs, hotels, holiday packages and trip planning' WHERE code = 'TRAVEL_TRANSPORT';
UPDATE categories SET description = 'Restaurants, deals, shows and play zones' WHERE code = 'DINING';

-- ===================== Home Repairs & Maintenance =====================
SELECT pg_temp.sub('HOME_REPAIRS','CLEANING','Cleaning','spray',1);
SELECT pg_temp.sub('HOME_REPAIRS','PEST_CONTROL','Pest Control','bug',2);
SELECT pg_temp.sub('HOME_REPAIRS','PAINTING','Painting','brush',3);
SELECT pg_temp.sub('HOME_REPAIRS','AC','AC Repair & Service','snow',4);
SELECT pg_temp.sub('HOME_REPAIRS','APPLIANCE','Appliance Repair','plug',5);
SELECT pg_temp.sub('HOME_REPAIRS','ELECTRICAL','Electrician','bolt',6);
SELECT pg_temp.sub('HOME_REPAIRS','PLUMBING','Plumber','pipe',7);
SELECT pg_temp.sub('HOME_REPAIRS','CARPENTRY','Carpenter','hammer',8);

SELECT pg_temp.item('HOME_REPAIRS','CLEANING','BATHROOM_CLEAN','Bathroom Cleaning','PER_UNIT',399,'per bathroom',1,'Tiles, fittings, mirror and floor scrubbed and disinfected','60 min');
SELECT pg_temp.item('HOME_REPAIRS','CLEANING','KITCHEN_CLEAN','Kitchen Deep Cleaning','FIXED',1299,NULL,2,'Cabinets, slab, sink, chimney exterior and appliances','3 hrs');
SELECT pg_temp.item('HOME_REPAIRS','CLEANING','HOME_DEEP_CLEAN','Full Home Deep Cleaning (2BHK)','FIXED',3499,NULL,3,'Every room, bathrooms and kitchen, machine scrubbing','6-8 hrs');
SELECT pg_temp.item('HOME_REPAIRS','CLEANING','SOFA_CLEAN','Sofa Cleaning','PER_UNIT',149,'per seat',4,'Shampoo and vacuum extraction','45 min');
SELECT pg_temp.item('HOME_REPAIRS','CLEANING','CARPET_CLEAN','Carpet Cleaning','STARTING_FROM',399,NULL,5);
SELECT pg_temp.item('HOME_REPAIRS','CLEANING','MATTRESS_CLEAN','Mattress Cleaning','PER_UNIT',349,'per mattress',6);

SELECT pg_temp.item('HOME_REPAIRS','PEST_CONTROL','GENERAL_PEST','General Pest Control','STARTING_FROM',999,NULL,1);
SELECT pg_temp.item('HOME_REPAIRS','PEST_CONTROL','COCKROACH','Cockroach Control','FIXED',899,NULL,2,'Gel + spray treatment, odourless');
SELECT pg_temp.item('HOME_REPAIRS','PEST_CONTROL','TERMITE','Termite Control','STARTING_FROM',2499,NULL,3);
SELECT pg_temp.item('HOME_REPAIRS','PEST_CONTROL','BED_BUGS','Bed Bug Treatment','FIXED',1499,NULL,4);
SELECT pg_temp.item('HOME_REPAIRS','PEST_CONTROL','MOSQUITO','Mosquito Control','FIXED',799,NULL,5);

SELECT pg_temp.item('HOME_REPAIRS','PAINTING','ROOM_PAINT','Single Room Painting','STARTING_FROM',3999,NULL,1);
SELECT pg_temp.item('HOME_REPAIRS','PAINTING','INTERIOR_PAINTING','Interior Painting','PER_UNIT',18,'per sq ft',2);
SELECT pg_temp.item('HOME_REPAIRS','PAINTING','TEXTURE','Wall Texture / Stencil','PER_UNIT',45,'per sq ft',3);
SELECT pg_temp.item('HOME_REPAIRS','PAINTING','EXTERIOR_PAINTING','Exterior Painting','QUOTE_REQUIRED',NULL,NULL,4);
SELECT pg_temp.item('HOME_REPAIRS','PAINTING','WATERPROOF','Waterproofing','QUOTE_REQUIRED',NULL,NULL,5);

SELECT pg_temp.item('HOME_REPAIRS','AC','AC_JET_SERVICE','AC Jet Service','FIXED',599,'per unit',1,'Foam + high-pressure jet cleaning of indoor unit','60 min');
SELECT pg_temp.item('HOME_REPAIRS','AC','AC_REPAIR','AC Repair','VISIT_PLUS_LABOUR',299,'visit',2);
SELECT pg_temp.item('HOME_REPAIRS','AC','AC_INSTALLATION','AC Installation','STARTING_FROM',1499,'per unit',3);
SELECT pg_temp.item('HOME_REPAIRS','AC','AC_UNINSTALLATION','AC Uninstallation','FIXED',699,'per unit',4);
SELECT pg_temp.item('HOME_REPAIRS','AC','GAS_REFILL','Gas Refill','STARTING_FROM',2499,NULL,5);
SELECT pg_temp.item('HOME_REPAIRS','AC','AC_AMC','Annual Maintenance (AMC)','PACKAGE',3999,'per year',6);

SELECT pg_temp.item('HOME_REPAIRS','APPLIANCE','WASHING_MACHINE','Washing Machine Repair','VISIT_PLUS_LABOUR',249,'visit',1);
SELECT pg_temp.item('HOME_REPAIRS','APPLIANCE','REFRIGERATOR','Refrigerator Repair','VISIT_PLUS_LABOUR',249,'visit',2);
SELECT pg_temp.item('HOME_REPAIRS','APPLIANCE','MICROWAVE','Microwave Repair','VISIT_PLUS_LABOUR',199,'visit',3);
SELECT pg_temp.item('HOME_REPAIRS','APPLIANCE','GEYSER','Geyser Repair','VISIT_PLUS_LABOUR',249,'visit',4);
SELECT pg_temp.item('HOME_REPAIRS','APPLIANCE','TV_REPAIR','TV Repair','VISIT_PLUS_LABOUR',299,'visit',5);
SELECT pg_temp.item('HOME_REPAIRS','APPLIANCE','RO_SERVICE','RO Water Purifier Service','FIXED',349,NULL,6);
SELECT pg_temp.item('HOME_REPAIRS','APPLIANCE','CHIMNEY','Chimney Cleaning','FIXED',699,NULL,7);

SELECT pg_temp.item('HOME_REPAIRS','ELECTRICAL','FAN_REPAIR','Fan Repair','STARTING_FROM',149,'per fan',1,'Regulator, capacitor, noise and speed issues','30 min');
SELECT pg_temp.item('HOME_REPAIRS','ELECTRICAL','FAN_INSTALLATION','Fan Installation','FIXED',249,'per fan',2);
SELECT pg_temp.item('HOME_REPAIRS','ELECTRICAL','LIGHT_REPAIR','Light / Tube Light Repair','STARTING_FROM',99,'per light',3);
SELECT pg_temp.item('HOME_REPAIRS','ELECTRICAL','LIGHT_INSTALLATION','Light Installation','STARTING_FROM',149,'per light',4);
SELECT pg_temp.item('HOME_REPAIRS','ELECTRICAL','SWITCH_SOCKET','Switch & Socket Repair','STARTING_FROM',99,'per point',5);
SELECT pg_temp.item('HOME_REPAIRS','ELECTRICAL','COMBO_SWITCHBOARD','Combo Switch Board Installation','FIXED',199,'per board',6);
SELECT pg_temp.item('HOME_REPAIRS','ELECTRICAL','WIRING','Wiring & Rewiring','RANGE',NULL,NULL,7);
SELECT pg_temp.item('HOME_REPAIRS','ELECTRICAL','MCB_REPAIR','MCB / Fuse Repair','FIXED',199,NULL,8);
SELECT pg_temp.item('HOME_REPAIRS','ELECTRICAL','DOORBELL','Doorbell Installation','FIXED',149,NULL,9);
SELECT pg_temp.item('HOME_REPAIRS','ELECTRICAL','INVERTER_SETUP','Inverter Setup','STARTING_FROM',799,NULL,10);
UPDATE service_items si SET min_price = 499, max_price = 5000 FROM subcategories s WHERE si.subcategory_id = s.id AND s.code = 'ELECTRICAL' AND si.code = 'WIRING';

SELECT pg_temp.item('HOME_REPAIRS','PLUMBING','TAP_REPAIR','Tap Repair','STARTING_FROM',199,'per tap',1);
SELECT pg_temp.item('HOME_REPAIRS','PLUMBING','PIPE_LEAKAGE','Pipe Leakage','VISIT_PLUS_LABOUR',149,'visit',2);
SELECT pg_temp.item('HOME_REPAIRS','PLUMBING','BATHROOM_FITTINGS','Bathroom Fittings','STARTING_FROM',299,'per fitting',3);
SELECT pg_temp.item('HOME_REPAIRS','PLUMBING','BLOCKAGE','Drain Blockage Removal','FIXED',249,NULL,4);
SELECT pg_temp.item('HOME_REPAIRS','PLUMBING','TOILET_REPAIR','Toilet / Flush Repair','STARTING_FROM',299,NULL,5);
SELECT pg_temp.item('HOME_REPAIRS','PLUMBING','WASH_BASIN','Wash Basin Installation','FIXED',399,NULL,6);
SELECT pg_temp.item('HOME_REPAIRS','PLUMBING','TANK_CLEANING','Water Tank Cleaning','PER_UNIT',599,'per tank',7);
SELECT pg_temp.item('HOME_REPAIRS','PLUMBING','MOTOR_INSTALL','Water Motor Installation','FIXED',499,NULL,8);
SELECT pg_temp.item('HOME_REPAIRS','PLUMBING','PIPELINE_INSTALLATION','Pipeline Installation','QUOTE_REQUIRED',NULL,NULL,9);

SELECT pg_temp.item('HOME_REPAIRS','CARPENTRY','FURNITURE_ASSEMBLY','Furniture Assembly','STARTING_FROM',399,NULL,1);
SELECT pg_temp.item('HOME_REPAIRS','CARPENTRY','DOOR_LOCK','Door & Lock Repair','STARTING_FROM',249,NULL,2);
SELECT pg_temp.item('HOME_REPAIRS','CARPENTRY','CURTAIN_ROD','Curtain Rod Installation','FIXED',199,NULL,3);
SELECT pg_temp.item('HOME_REPAIRS','CARPENTRY','BED_REPAIR','Bed / Cot Repair','STARTING_FROM',349,NULL,4);
SELECT pg_temp.item('HOME_REPAIRS','CARPENTRY','DRILLING','Wall Drilling & Hanging','PER_UNIT',99,'per point',5);
SELECT pg_temp.item('HOME_REPAIRS','CARPENTRY','FURNITURE_REPAIR','Furniture Repair','QUOTE_REQUIRED',NULL,NULL,6);

-- ===================== Daily Errands & Shopping =====================
SELECT pg_temp.sub('DAILY_ERRANDS','GROCERY','Grocery & Essentials','basket',1);
SELECT pg_temp.sub('DAILY_ERRANDS','MEDICINES','Medicine Pickup','pills',2);
SELECT pg_temp.sub('DAILY_ERRANDS','PICKUP_DROP','Pickup & Drop','route',3);
SELECT pg_temp.sub('DAILY_ERRANDS','LAUNDRY','Laundry & Dry Cleaning','shirt',4);
SELECT pg_temp.sub('DAILY_ERRANDS','BILLS','Bills & Paperwork','file',5);
SELECT pg_temp.sub('DAILY_ERRANDS','SHOPPER','Personal Shopping','cart',6);
SELECT pg_temp.item('DAILY_ERRANDS','GROCERY','GROCERY_DELIVERY','Grocery Pickup & Delivery','FIXED',79,'per trip',1,'Item cost paid separately at actuals');
SELECT pg_temp.item('DAILY_ERRANDS','GROCERY','GROCERY_RUN','Monthly Grocery Shopping','FIXED',199,NULL,2);
SELECT pg_temp.item('DAILY_ERRANDS','MEDICINES','MEDICINE_PICKUP','Medicine Pickup & Delivery','FIXED',79,'per trip',1);
SELECT pg_temp.item('DAILY_ERRANDS','MEDICINES','MEDICINE_REFILL','Monthly Medicine Refill','FIXED',99,'per month',2);
SELECT pg_temp.item('DAILY_ERRANDS','PICKUP_DROP','DOCUMENT_PICKUP','Document Pickup & Drop','STARTING_FROM',149,NULL,1);
SELECT pg_temp.item('DAILY_ERRANDS','PICKUP_DROP','KEYS_DROP','Keys / Parcel Hand-over','FIXED',129,NULL,2);
SELECT pg_temp.item('DAILY_ERRANDS','LAUNDRY','WASH_FOLD','Wash & Fold','PER_UNIT',69,'per kg',1);
SELECT pg_temp.item('DAILY_ERRANDS','LAUNDRY','DRY_CLEAN','Dry Cleaning','STARTING_FROM',99,'per piece',2);
SELECT pg_temp.item('DAILY_ERRANDS','LAUNDRY','IRONING','Ironing','PER_UNIT',12,'per piece',3);
SELECT pg_temp.item('DAILY_ERRANDS','BILLS','BILL_ASSIST','Utility Bill Payment Help','FIXED',49,NULL,1);
SELECT pg_temp.item('DAILY_ERRANDS','BILLS','GOVT_OFFICE','Government Office Errand','FIXED',299,NULL,2);
SELECT pg_temp.item('DAILY_ERRANDS','SHOPPER','SHOPPER_HOURLY','Personal Shopper','HOURLY',299,NULL,1);
SELECT pg_temp.item('DAILY_ERRANDS','SHOPPER','GIFT_SHOP','Gift Shopping & Wrapping','FIXED',199,NULL,2,'Gift cost paid separately at actuals');

-- ===================== Tickets & Reservations =====================
SELECT pg_temp.sub('TICKETS','TRAVEL_TICKETS','Bus / Train / Flight','ticket',1);
SELECT pg_temp.sub('TICKETS','ENTERTAINMENT_TICKETS','Movies & Events','film',2);
SELECT pg_temp.sub('TICKETS','DARSHAN','Temple Darshan','temple',3);
SELECT pg_temp.sub('TICKETS','SPORTS','Sports Matches','trophy',4);
SELECT pg_temp.item('TICKETS','TRAVEL_TICKETS','BUS_TICKET','Bus Ticket Booking','QUOTE_REQUIRED',NULL,NULL,1,'Fare confirmed by your agent before booking');
SELECT pg_temp.item('TICKETS','TRAVEL_TICKETS','TRAIN_TICKET','Train Ticket Booking','QUOTE_REQUIRED',NULL,NULL,2,'Fare confirmed by your agent before booking');
SELECT pg_temp.item('TICKETS','TRAVEL_TICKETS','FLIGHT_TICKET','Flight Ticket Booking','QUOTE_REQUIRED',NULL,NULL,3,'Fare confirmed by your agent before booking');
SELECT pg_temp.item('TICKETS','ENTERTAINMENT_TICKETS','MOVIE_TICKETS','Movie Ticket Booking','FIXED',49,'service fee',1,'Ticket price extra');
SELECT pg_temp.item('TICKETS','ENTERTAINMENT_TICKETS','CONCERT_TICKETS','Concert & Event Tickets','QUOTE_REQUIRED',NULL,NULL,2);
SELECT pg_temp.item('TICKETS','DARSHAN','DARSHAN_BOOKING','Darshan Slot Booking Help','FIXED',199,'service fee',1);
SELECT pg_temp.item('TICKETS','SPORTS','CRICKET_TICKETS','Cricket Match Tickets','QUOTE_REQUIRED',NULL,NULL,1);

-- ===================== Dining & Entertainment =====================
SELECT pg_temp.sub('DINING','RESERVATIONS','Restaurants','dining',2);
SELECT pg_temp.sub('DINING','PRIVATE_DINING','Private Chef & Dining','chef',3);
SELECT pg_temp.sub('DINING','SHOWS','Events & Shows','music',4);
SELECT pg_temp.sub('DINING','PLAY_ZONES','Play Zones','game',5);
SELECT pg_temp.item('DINING','RESERVATIONS','TABLE_BOOKING','Table Reservation','FIXED',0,NULL,1,'Free - your agent confirms the table');
SELECT pg_temp.item('DINING','RESERVATIONS','BUFFET','Buffet Booking','PER_UNIT',799,'per person',2);
SELECT pg_temp.item('DINING','RESERVATIONS','CANDLELIGHT','Candle-light Dinner Setup','FIXED',2999,NULL,3);
SELECT pg_temp.item('DINING','RESERVATIONS','PARTY_TABLE','Group / Party Reservation','QUOTE_REQUIRED',NULL,NULL,4);
SELECT pg_temp.item('DINING','PRIVATE_DINING','PRIVATE_CHEF','Private Chef at Home','STARTING_FROM',2499,NULL,1);
SELECT pg_temp.item('DINING','PRIVATE_DINING','BBQ_COUNTER','Live BBQ Counter','QUOTE_REQUIRED',NULL,NULL,2);
SELECT pg_temp.item('DINING','SHOWS','STANDUP','Stand-up Comedy Show','PER_UNIT',499,'per ticket',1);
SELECT pg_temp.item('DINING','SHOWS','LIVE_MUSIC','Live Music Night','PER_UNIT',599,'per ticket',2);
SELECT pg_temp.item('DINING','SHOWS','WORKSHOPS','Weekend Workshops','PER_UNIT',799,'per person',3);
SELECT pg_temp.item('DINING','PLAY_ZONES','KIDS_PLAY','Kids Play Zone Pass','PER_UNIT',499,'per child',1);
SELECT pg_temp.item('DINING','PLAY_ZONES','TRAMPOLINE','Trampoline Park','PER_UNIT',699,'per person',2);
SELECT pg_temp.item('DINING','PLAY_ZONES','GAMING_ZONE','Gaming Zone','HOURLY',399,NULL,3);
SELECT pg_temp.item('DINING','PLAY_ZONES','BOWLING','Bowling (2 games)','PER_UNIT',349,'per person',4);
SELECT pg_temp.item('DINING','PLAY_ZONES','GO_KARTING','Go-Karting','PER_UNIT',899,'per ride',5);

-- ===================== Beauty & Personal Care =====================
SELECT pg_temp.sub('BEAUTY','SALON_AT_HOME','Salon for Women','sparkle',1);
SELECT pg_temp.sub('BEAUTY','MEN_GROOMING','Men''s Grooming','scissors',2);
SELECT pg_temp.sub('BEAUTY','SPA','Spa & Massage','spa',3);
SELECT pg_temp.sub('BEAUTY','MAKEUP','Makeup & Bridal','lipstick',4);
SELECT pg_temp.sub('BEAUTY','NAILS','Nails & Mehendi','nail',5);
SELECT pg_temp.item('BEAUTY','SALON_AT_HOME','HAIRCUT_HOME','Haircut at Home','STARTING_FROM',299,NULL,1);
SELECT pg_temp.item('BEAUTY','SALON_AT_HOME','FACIAL','Facial & Cleanup','STARTING_FROM',799,NULL,2,NULL,'60 min');
SELECT pg_temp.item('BEAUTY','SALON_AT_HOME','WAXING','Full Body Waxing','FIXED',999,NULL,3);
SELECT pg_temp.item('BEAUTY','SALON_AT_HOME','THREADING','Threading','FIXED',49,NULL,4);
SELECT pg_temp.item('BEAUTY','SALON_AT_HOME','MANICURE','Manicure','FIXED',399,NULL,5);
SELECT pg_temp.item('BEAUTY','SALON_AT_HOME','PEDICURE','Pedicure','FIXED',499,NULL,6);
SELECT pg_temp.item('BEAUTY','SALON_AT_HOME','HAIR_SPA','Hair Spa','FIXED',699,NULL,7);
SELECT pg_temp.item('BEAUTY','MEN_GROOMING','MEN_HAIRCUT','Haircut','FIXED',199,NULL,1);
SELECT pg_temp.item('BEAUTY','MEN_GROOMING','BEARD','Beard Styling','FIXED',149,NULL,2);
SELECT pg_temp.item('BEAUTY','MEN_GROOMING','MEN_FACIAL','Men''s Facial','FIXED',599,NULL,3);
SELECT pg_temp.item('BEAUTY','MEN_GROOMING','MEN_HAIR_COLOUR','Hair Colour','FIXED',399,NULL,4);
SELECT pg_temp.item('BEAUTY','SPA','SWEDISH','Swedish Massage','FIXED',1499,NULL,1,NULL,'60 min');
SELECT pg_temp.item('BEAUTY','SPA','DEEP_TISSUE','Deep Tissue Massage','FIXED',1799,NULL,2,NULL,'60 min');
SELECT pg_temp.item('BEAUTY','SPA','HEAD_MASSAGE','Head & Shoulder Massage','FIXED',399,NULL,3,NULL,'30 min');
SELECT pg_temp.item('BEAUTY','MAKEUP','PARTY_MAKEUP','Party Makeup','FIXED',1999,NULL,1);
SELECT pg_temp.item('BEAUTY','MAKEUP','BRIDAL_MAKEUP','Bridal Makeup','STARTING_FROM',9999,NULL,2);
SELECT pg_temp.item('BEAUTY','MAKEUP','SAREE_DRAPING','Saree Draping','FIXED',499,NULL,3);
SELECT pg_temp.item('BEAUTY','NAILS','NAIL_ART','Nail Art','FIXED',599,NULL,1);
SELECT pg_temp.item('BEAUTY','NAILS','GEL_NAILS','Gel Nail Extensions','FIXED',1199,NULL,2);
SELECT pg_temp.item('BEAUTY','NAILS','MEHENDI_HANDS','Mehendi (both hands)','FIXED',799,NULL,3);

-- ===================== Events & Celebrations =====================
SELECT pg_temp.sub('EVENTS','BIRTHDAY','Birthday Parties','cake',1);
SELECT pg_temp.sub('EVENTS','DECORATION','Decoration','balloon',2);
SELECT pg_temp.sub('EVENTS','ANNIVERSARY','Anniversary & Surprises','heart',3);
SELECT pg_temp.sub('EVENTS','BABY_SHOWER','Baby Shower & Naming','baby',4);
SELECT pg_temp.sub('EVENTS','ENTERTAINERS','Entertainers & DJ','music',5);
SELECT pg_temp.sub('EVENTS','CAKES_GIFTS','Cakes & Return Gifts','gift',6);
SELECT pg_temp.sub('EVENTS','CORPORATE','Corporate Events','briefcase',7);
SELECT pg_temp.item('EVENTS','BIRTHDAY','BIRTHDAY_PACKAGE','Birthday Party Package','PACKAGE',7999,NULL,1,'Decor, cake table, entertainer and coordination');
SELECT pg_temp.item('EVENTS','BIRTHDAY','THEME_PARTY','Theme Party Setup','STARTING_FROM',9999,NULL,2);
SELECT pg_temp.item('EVENTS','BIRTHDAY','BIRTHDAY_DECOR','Birthday Balloon Decor','FIXED',1999,NULL,3);
SELECT pg_temp.item('EVENTS','DECORATION','BALLOON_DECOR','Balloon Decoration','STARTING_FROM',1499,NULL,1);
SELECT pg_temp.item('EVENTS','DECORATION','FLOWER_DECOR','Flower Decoration','STARTING_FROM',3999,NULL,2);
SELECT pg_temp.item('EVENTS','DECORATION','LIGHTING','Lighting Setup','FIXED',2499,NULL,3);
SELECT pg_temp.item('EVENTS','ANNIVERSARY','ROOM_SURPRISE','Surprise Room Decoration','FIXED',2499,NULL,1);
SELECT pg_temp.item('EVENTS','ANNIVERSARY','ANNIVERSARY_DINNER','Anniversary Dinner Setup','FIXED',3999,NULL,2);
SELECT pg_temp.item('EVENTS','BABY_SHOWER','BABY_SHOWER_DECOR','Baby Shower Decoration','FIXED',4999,NULL,1);
SELECT pg_temp.item('EVENTS','BABY_SHOWER','NAMING_CEREMONY','Naming Ceremony Setup','FIXED',5999,NULL,2);
SELECT pg_temp.item('EVENTS','ENTERTAINERS','DJ','DJ (4 hours)','FIXED',4999,NULL,1);
SELECT pg_temp.item('EVENTS','ENTERTAINERS','MAGICIAN','Magician / Clown','FIXED',3999,NULL,2);
SELECT pg_temp.item('EVENTS','ENTERTAINERS','ANCHOR','Event Anchor','FIXED',4999,NULL,3);
SELECT pg_temp.item('EVENTS','CAKES_GIFTS','DESIGNER_CAKE','Designer Cake (1 kg)','FIXED',1199,NULL,1);
SELECT pg_temp.item('EVENTS','CAKES_GIFTS','RETURN_GIFTS','Return Gifts','PER_UNIT',149,'per piece',2);
SELECT pg_temp.item('EVENTS','CORPORATE','CORPORATE_EVENT','Corporate Event Management','QUOTE_REQUIRED',NULL,NULL,1);
SELECT pg_temp.item('EVENTS','CORPORATE','TEAM_OUTING','Team Outing','PER_UNIT',1499,'per person',2);

-- ===================== Wedding & Functions =====================
SELECT pg_temp.sub('WEDDING_CATERING','WEDDING_PLANNING','Wedding','rings',1);
SELECT pg_temp.sub('WEDDING_CATERING','HALDI','Haldi','flower',2);
SELECT pg_temp.sub('WEDDING_CATERING','MEHENDI','Mehendi','nail',3);
SELECT pg_temp.sub('WEDDING_CATERING','SANGEET','Sangeet','music',4);
SELECT pg_temp.sub('WEDDING_CATERING','ENGAGEMENT','Engagement','rings',5);
SELECT pg_temp.sub('WEDDING_CATERING','RECEPTION','Reception','party',6);
SELECT pg_temp.sub('WEDDING_CATERING','HALF_SAREE','Half Saree Function','saree',7);
SELECT pg_temp.sub('WEDDING_CATERING','BIRTHDAY_FUNCTION','Birthday Function','cake',8);
SELECT pg_temp.sub('WEDDING_CATERING','CATERING','Catering','dish',9);
SELECT pg_temp.sub('WEDDING_CATERING','VENUES','Venues','building',10);
SELECT pg_temp.item('WEDDING_CATERING','WEDDING_PLANNING','WEDDING_PLANNER','Full Wedding Planning','QUOTE_REQUIRED',NULL,NULL,1);
SELECT pg_temp.item('WEDDING_CATERING','WEDDING_PLANNING','MANDAP_DECOR','Mandap Decoration','STARTING_FROM',24999,NULL,2);
SELECT pg_temp.item('WEDDING_CATERING','WEDDING_PLANNING','PANDIT','Pandit / Purohit Services','FIXED',5100,NULL,3);
SELECT pg_temp.item('WEDDING_CATERING','WEDDING_PLANNING','BRIDAL_ENTRY','Bridal Entry Setup','FIXED',9999,NULL,4);
SELECT pg_temp.item('WEDDING_CATERING','WEDDING_PLANNING','WEDDING_DJ','Wedding DJ & Sound','FIXED',14999,NULL,5);
SELECT pg_temp.item('WEDDING_CATERING','HALDI','HALDI_PACKAGE','Haldi Function Package','PACKAGE',19999,NULL,1);
SELECT pg_temp.item('WEDDING_CATERING','HALDI','HALDI_DECOR','Haldi Decoration','FIXED',6999,NULL,2);
SELECT pg_temp.item('WEDDING_CATERING','HALDI','DHOL','Dhol Players','FIXED',4999,NULL,3);
SELECT pg_temp.item('WEDDING_CATERING','MEHENDI','MEHENDI_ARTIST','Bridal Mehendi Artist','FIXED',4999,NULL,1);
SELECT pg_temp.item('WEDDING_CATERING','MEHENDI','GUEST_MEHENDI','Guest Mehendi','PER_UNIT',199,'per guest',2);
SELECT pg_temp.item('WEDDING_CATERING','MEHENDI','MEHENDI_DECOR','Mehendi Decoration','FIXED',6999,NULL,3);
SELECT pg_temp.item('WEDDING_CATERING','SANGEET','CHOREOGRAPHER','Sangeet Choreographer','FIXED',14999,NULL,1);
SELECT pg_temp.item('WEDDING_CATERING','SANGEET','SANGEET_DJ','DJ & Lights','FIXED',12999,NULL,2);
SELECT pg_temp.item('WEDDING_CATERING','SANGEET','SANGEET_STAGE','Stage & Decor','FIXED',19999,NULL,3);
SELECT pg_temp.item('WEDDING_CATERING','ENGAGEMENT','ENG_PACKAGE','Engagement Package','PACKAGE',39999,NULL,1);
SELECT pg_temp.item('WEDDING_CATERING','ENGAGEMENT','ENG_DECOR','Engagement Decoration','FIXED',14999,NULL,2);
SELECT pg_temp.item('WEDDING_CATERING','ENGAGEMENT','RING_PLATTER','Ring Platter & Props','FIXED',1499,NULL,3);
SELECT pg_temp.item('WEDDING_CATERING','RECEPTION','RECEPTION_STAGE','Reception Stage Decor','FIXED',29999,NULL,1);
SELECT pg_temp.item('WEDDING_CATERING','RECEPTION','RECEPTION_LIGHTS','Lighting & Effects','FIXED',9999,NULL,2);
SELECT pg_temp.item('WEDDING_CATERING','RECEPTION','LIVE_BAND','Live Band','FIXED',24999,NULL,3);
SELECT pg_temp.item('WEDDING_CATERING','HALF_SAREE','HALF_SAREE_PACKAGE','Complete Function Package','PACKAGE',34999,NULL,1);
SELECT pg_temp.item('WEDDING_CATERING','HALF_SAREE','HALF_SAREE_DECOR','Half Saree Function Decoration','FIXED',9999,NULL,2);
SELECT pg_temp.item('WEDDING_CATERING','HALF_SAREE','HALF_SAREE_MAKEUP','Makeup & Draping','FIXED',4999,NULL,3);
SELECT pg_temp.item('WEDDING_CATERING','BIRTHDAY_FUNCTION','BDAY_FUNCTION_PACKAGE','Birthday Function Package','PACKAGE',24999,NULL,1);
SELECT pg_temp.item('WEDDING_CATERING','BIRTHDAY_FUNCTION','BDAY_STAGE','Stage & Balloon Decor','FIXED',7999,NULL,2);
SELECT pg_temp.item('WEDDING_CATERING','CATERING','VEG_PLATE','Veg Catering','PER_UNIT',350,'per plate',1);
SELECT pg_temp.item('WEDDING_CATERING','CATERING','NONVEG_PLATE','Non-Veg Catering','PER_UNIT',450,'per plate',2);
SELECT pg_temp.item('WEDDING_CATERING','CATERING','LIVE_COUNTERS','Live Food Counters','QUOTE_REQUIRED',NULL,NULL,3);
SELECT pg_temp.item('WEDDING_CATERING','CATERING','WEDDING_CATERING','Full Wedding Catering','QUOTE_REQUIRED',NULL,NULL,4);
SELECT pg_temp.item('WEDDING_CATERING','VENUES','FUNCTION_HALL','Function Hall (up to 200 guests)','STARTING_FROM',49999,NULL,1);
SELECT pg_temp.item('WEDDING_CATERING','VENUES','BANQUET','Banquet Hall Booking','QUOTE_REQUIRED',NULL,NULL,2);
SELECT pg_temp.item('WEDDING_CATERING','VENUES','FARMHOUSE','Farmhouse Venue','QUOTE_REQUIRED',NULL,NULL,3);

-- ===================== Photography & Videography =====================
SELECT pg_temp.sub('PHOTOGRAPHY','WEDDING_PHOTO','Wedding Photography','camera',1);
SELECT pg_temp.sub('PHOTOGRAPHY','PRE_WEDDING','Pre-Wedding Shoot','heart',2);
SELECT pg_temp.sub('PHOTOGRAPHY','EVENT_PHOTOGRAPHY','Event Photography','camera',3);
SELECT pg_temp.sub('PHOTOGRAPHY','VIDEOGRAPHY','Videography & Films','video',4);
SELECT pg_temp.sub('PHOTOGRAPHY','BIRTHDAY_PHOTO','Birthday & Kids','cake',5);
SELECT pg_temp.sub('PHOTOGRAPHY','MATERNITY_NEWBORN','Maternity & Newborn','baby',6);
SELECT pg_temp.sub('PHOTOGRAPHY','PORTRAIT','Portraits & Fashion','image',7);
SELECT pg_temp.sub('PHOTOGRAPHY','PRODUCT_PHOTO','Product & Food','image',8);
SELECT pg_temp.sub('PHOTOGRAPHY','DRONE','Drone Coverage','drone',9);
SELECT pg_temp.sub('PHOTOGRAPHY','CORPORATE_PHOTO','Corporate & Headshots','briefcase',10);
SELECT pg_temp.item('PHOTOGRAPHY','WEDDING_PHOTO','CANDID_DAY','Candid Photography','PER_UNIT',24999,'per day',1);
SELECT pg_temp.item('PHOTOGRAPHY','WEDDING_PHOTO','TRADITIONAL_DAY','Traditional Photography','PER_UNIT',14999,'per day',2);
SELECT pg_temp.item('PHOTOGRAPHY','WEDDING_PHOTO','WEDDING_ALBUM','Premium Album (40 sheets)','FIXED',9999,NULL,3);
SELECT pg_temp.item('PHOTOGRAPHY','WEDDING_PHOTO','WEDDING_COMBO','Photo + Video Wedding Package','PACKAGE',69999,NULL,4);
SELECT pg_temp.item('PHOTOGRAPHY','PRE_WEDDING','PREWED_HALF','Half-day Pre-Wedding Shoot','FIXED',14999,NULL,1);
SELECT pg_temp.item('PHOTOGRAPHY','PRE_WEDDING','PREWED_FULL','Full-day Shoot (2 locations)','FIXED',29999,NULL,2);
SELECT pg_temp.item('PHOTOGRAPHY','PRE_WEDDING','PREWED_FILM','Pre-Wedding Film','FIXED',34999,NULL,3);
SELECT pg_temp.item('PHOTOGRAPHY','EVENT_PHOTOGRAPHY','EVENT_SHOOT','Event Photo Shoot (4 hrs)','STARTING_FROM',6999,NULL,1);
SELECT pg_temp.item('PHOTOGRAPHY','VIDEOGRAPHY','EVENT_VIDEO','Event Videography (4 hrs)','STARTING_FROM',7999,NULL,1);
SELECT pg_temp.item('PHOTOGRAPHY','VIDEOGRAPHY','CINEMATIC','Cinematic Highlight Film','FIXED',19999,NULL,2);
SELECT pg_temp.item('PHOTOGRAPHY','VIDEOGRAPHY','LIVE_STREAM','Live Streaming','FIXED',9999,NULL,3);
SELECT pg_temp.item('PHOTOGRAPHY','BIRTHDAY_PHOTO','BDAY_SHOOT','Birthday Shoot (3 hrs)','FIXED',4999,NULL,1);
SELECT pg_temp.item('PHOTOGRAPHY','BIRTHDAY_PHOTO','CAKE_SMASH','Cake Smash Shoot','FIXED',3999,NULL,2);
SELECT pg_temp.item('PHOTOGRAPHY','MATERNITY_NEWBORN','MATERNITY','Maternity Shoot','FIXED',7999,NULL,1);
SELECT pg_temp.item('PHOTOGRAPHY','MATERNITY_NEWBORN','NEWBORN','Newborn Shoot','FIXED',8999,NULL,2);
SELECT pg_temp.item('PHOTOGRAPHY','PORTRAIT','PORTRAIT_SESSION','Portrait Session (1 hr)','FIXED',2999,NULL,1);
SELECT pg_temp.item('PHOTOGRAPHY','PORTRAIT','PORTFOLIO','Model Portfolio','FIXED',9999,NULL,2);
SELECT pg_temp.item('PHOTOGRAPHY','PRODUCT_PHOTO','PRODUCT_SHOOT','Product Shoot (10 photos)','FIXED',3999,NULL,1);
SELECT pg_temp.item('PHOTOGRAPHY','PRODUCT_PHOTO','FOOD_SHOOT','Restaurant / Food Shoot','FIXED',5999,NULL,2);
SELECT pg_temp.item('PHOTOGRAPHY','DRONE','DRONE_EVENT','Drone Coverage (per event)','FIXED',6999,NULL,1);
SELECT pg_temp.item('PHOTOGRAPHY','CORPORATE_PHOTO','HEADSHOTS','Corporate Headshots','PER_UNIT',599,'per person',1);
SELECT pg_temp.item('PHOTOGRAPHY','CORPORATE_PHOTO','CORP_EVENT','Corporate Event Coverage','FIXED',9999,NULL,2);

-- ===================== Legal Services Assistance =====================
SELECT pg_temp.sub('LEGAL','LAWYER_REFERRAL','Lawyer Consultation','gavel',1);
SELECT pg_temp.sub('LEGAL','DOCUMENT_DRAFTING','Agreements & Drafting','file',2);
SELECT pg_temp.sub('LEGAL','PROPERTY_LEGAL','Property & Registration','home',3);
SELECT pg_temp.sub('LEGAL','FAMILY_LAW','Family & Marriage','family',4);
SELECT pg_temp.sub('LEGAL','CONSUMER','Consumer & Disputes','shield',5);
SELECT pg_temp.sub('LEGAL','BUSINESS_LEGAL','Business & Company','briefcase',6);
SELECT pg_temp.sub('LEGAL','NOTARY','Notary & Affidavits','file',7);
SELECT pg_temp.item('LEGAL','LAWYER_REFERRAL','LAWYER_CONSULT','Lawyer Consultation (30 min)','FIXED',999,NULL,1,'With a qualified, verified advocate');
SELECT pg_temp.item('LEGAL','LAWYER_REFERRAL','VIDEO_CONSULT','Video Consultation with Lawyer','FIXED',699,NULL,2);
SELECT pg_temp.item('LEGAL','DOCUMENT_DRAFTING','RENTAL_AGREEMENT','Rental Agreement Drafting','FIXED',499,NULL,1,'Stamp duty extra at actuals');
SELECT pg_temp.item('LEGAL','DOCUMENT_DRAFTING','SALE_AGREEMENT','Sale Agreement Drafting','FIXED',2499,NULL,2);
SELECT pg_temp.item('LEGAL','DOCUMENT_DRAFTING','LEGAL_NOTICE','Legal Notice Drafting','FIXED',1999,NULL,3);
SELECT pg_temp.item('LEGAL','PROPERTY_LEGAL','PROPERTY_VERIFY','Property Document Verification','FIXED',4999,NULL,1);
SELECT pg_temp.item('LEGAL','PROPERTY_LEGAL','REGISTRATION_HELP','Property Registration Assistance','FIXED',2999,NULL,2);
SELECT pg_temp.item('LEGAL','PROPERTY_LEGAL','MUTATION','Mutation / Khata Transfer Help','FIXED',1999,NULL,3);
SELECT pg_temp.item('LEGAL','FAMILY_LAW','MARRIAGE_REG','Marriage Registration Assistance','FIXED',2499,NULL,1);
SELECT pg_temp.item('LEGAL','FAMILY_LAW','FAMILY_CONSULT','Family Lawyer Consultation','FIXED',1499,NULL,2);
SELECT pg_temp.item('LEGAL','CONSUMER','CONSUMER_COMPLAINT','Consumer Complaint Filing Assistance','FIXED',1499,NULL,1);
SELECT pg_temp.item('LEGAL','CONSUMER','CHEQUE_BOUNCE','Cheque Bounce Notice','FIXED',1999,NULL,2);
SELECT pg_temp.item('LEGAL','BUSINESS_LEGAL','COMPANY_REG','Private Limited Registration Assistance','STARTING_FROM',6999,NULL,1);
SELECT pg_temp.item('LEGAL','BUSINESS_LEGAL','TRADEMARK','Trademark Filing Assistance','FIXED',4999,NULL,2);
SELECT pg_temp.item('LEGAL','NOTARY','AFFIDAVIT','Affidavit Drafting & Notary','FIXED',399,NULL,1);
SELECT pg_temp.item('LEGAL','NOTARY','NOTARY_VISIT','Notary at Home','FIXED',699,NULL,2);

-- ===================== Financial Services Assistance =====================
SELECT pg_temp.sub('FINANCIAL','TAX_FILING','Tax Filing & GST','calculator',1);
SELECT pg_temp.sub('FINANCIAL','LOANS_INSURANCE','Loans & Insurance','bank',2);
SELECT pg_temp.sub('FINANCIAL','INVESTMENT','Investment Advisor Appointments','chart',3);
SELECT pg_temp.sub('FINANCIAL','KYC_DOCS','PAN, Aadhaar & KYC Help','file',4);
SELECT pg_temp.sub('FINANCIAL','ACCOUNTING','Bookkeeping & Accounting','calculator',5);
SELECT pg_temp.item('FINANCIAL','TAX_FILING','ITR_APPOINTMENT','ITR Filing (Salaried) with CA','FIXED',999,NULL,1);
SELECT pg_temp.item('FINANCIAL','TAX_FILING','ITR_BUSINESS','ITR Filing (Business) with CA','STARTING_FROM',2499,NULL,2);
SELECT pg_temp.item('FINANCIAL','TAX_FILING','GST_REG','GST Registration Assistance','FIXED',1499,NULL,3);
SELECT pg_temp.item('FINANCIAL','TAX_FILING','GST_RETURNS','Monthly GST Returns','PER_UNIT',999,'per month',4);
SELECT pg_temp.item('FINANCIAL','LOANS_INSURANCE','LOAN_ASSIST','Loan Application Assistance','FIXED',499,NULL,1,'Paperwork help with authorised lenders only');
SELECT pg_temp.item('FINANCIAL','LOANS_INSURANCE','INSURANCE_RENEWAL','Insurance Renewal Assistance','FIXED',199,NULL,2);
SELECT pg_temp.item('FINANCIAL','LOANS_INSURANCE','HEALTH_INSURANCE_APPT','Health Insurance Advisor Appointment','FIXED',0,NULL,3);
SELECT pg_temp.item('FINANCIAL','INVESTMENT','ADVISOR_APPT','SEBI-Registered Advisor Appointment','QUOTE_REQUIRED',NULL,NULL,1);
SELECT pg_temp.item('FINANCIAL','KYC_DOCS','PAN_HELP','New PAN / Correction Help','FIXED',149,NULL,1);
SELECT pg_temp.item('FINANCIAL','KYC_DOCS','AADHAAR_UPDATE','Aadhaar Update Assistance','FIXED',149,NULL,2);
SELECT pg_temp.item('FINANCIAL','ACCOUNTING','BOOKKEEPING','Bookkeeping','PER_UNIT',2999,'per month',1);
SELECT pg_temp.item('FINANCIAL','ACCOUNTING','PAYROLL','Payroll Processing','QUOTE_REQUIRED',NULL,NULL,2);

-- ===================== Travel & Transport =====================
SELECT pg_temp.sub('TRAVEL_TRANSPORT','BOOK_TICKETS','Book a Ticket','ticket',1);
SELECT pg_temp.sub('TRAVEL_TRANSPORT','CABS','Cabs & Outstation','car',2);
SELECT pg_temp.sub('TRAVEL_TRANSPORT','DRIVER_ON_DEMAND','Driver on Demand','steering',3);
SELECT pg_temp.sub('TRAVEL_TRANSPORT','HOTELS','Hotels & Stays','hotel',4);
SELECT pg_temp.sub('TRAVEL_TRANSPORT','HOLIDAY_PACKAGES','Holiday Packages','beach',5);
SELECT pg_temp.sub('TRAVEL_TRANSPORT','TOUR_PLANNING','Trip Planning (India)','map',6);
SELECT pg_temp.sub('TRAVEL_TRANSPORT','INTERNATIONAL','International Trips','globe',7);
SELECT pg_temp.sub('TRAVEL_TRANSPORT','VISA_TRAVEL','Visa & Travel Docs','passport',8);
SELECT pg_temp.item('TRAVEL_TRANSPORT','BOOK_TICKETS','BUS_BOOKING','Bus Ticket','QUOTE_REQUIRED',NULL,NULL,1,'Fare confirmed by your agent');
SELECT pg_temp.item('TRAVEL_TRANSPORT','BOOK_TICKETS','TRAIN_BOOKING','Train Ticket','QUOTE_REQUIRED',NULL,NULL,2,'Fare confirmed by your agent');
SELECT pg_temp.item('TRAVEL_TRANSPORT','BOOK_TICKETS','FLIGHT_BOOKING','Flight Ticket','QUOTE_REQUIRED',NULL,NULL,3,'Fare confirmed by your agent');
SELECT pg_temp.item('TRAVEL_TRANSPORT','CABS','AIRPORT_TRANSFER','Airport Drop / Pickup','STARTING_FROM',699,NULL,1);
SELECT pg_temp.item('TRAVEL_TRANSPORT','CABS','LOCAL_RENTAL','Local Cab (8 hrs / 80 km)','FIXED',2199,NULL,2);
SELECT pg_temp.item('TRAVEL_TRANSPORT','CABS','OUTSTATION','Outstation Cab','PER_UNIT',12,'per km',3);
SELECT pg_temp.item('TRAVEL_TRANSPORT','DRIVER_ON_DEMAND','HOURLY_DRIVER','Hourly Driver','HOURLY',199,NULL,1);
SELECT pg_temp.item('TRAVEL_TRANSPORT','DRIVER_ON_DEMAND','DRIVER_DAY','Driver for a Day','FIXED',999,NULL,2);
SELECT pg_temp.item('TRAVEL_TRANSPORT','HOTELS','HOTEL_BOOKING','Hotel Booking Assistance','QUOTE_REQUIRED',NULL,NULL,1);
SELECT pg_temp.item('TRAVEL_TRANSPORT','HOTELS','RESORT_STAY','Weekend Resort Stay','STARTING_FROM',4999,'per night',2);
SELECT pg_temp.item('TRAVEL_TRANSPORT','HOTELS','HOMESTAY','Homestays & Villas','QUOTE_REQUIRED',NULL,NULL,3);
SELECT pg_temp.item('TRAVEL_TRANSPORT','HOLIDAY_PACKAGES','GOA_PKG','Goa 3N / 4D','PACKAGE',14999,'per person',1);
SELECT pg_temp.item('TRAVEL_TRANSPORT','HOLIDAY_PACKAGES','KERALA_PKG','Kerala 5N / 6D','PACKAGE',22999,'per person',2);
SELECT pg_temp.item('TRAVEL_TRANSPORT','HOLIDAY_PACKAGES','KASHMIR_PKG','Kashmir 5N / 6D','PACKAGE',27999,'per person',3);
SELECT pg_temp.item('TRAVEL_TRANSPORT','HOLIDAY_PACKAGES','ANDAMAN_PKG','Andaman 4N / 5D','PACKAGE',29999,'per person',4);
SELECT pg_temp.item('TRAVEL_TRANSPORT','HOLIDAY_PACKAGES','TIRUPATI_PKG','Tirupati Darshan 2D','PACKAGE',4999,'per person',5);
SELECT pg_temp.item('TRAVEL_TRANSPORT','TOUR_PLANNING','CUSTOM_TRIP','Custom Trip Planning','FIXED',999,'planning fee',1);
SELECT pg_temp.item('TRAVEL_TRANSPORT','TOUR_PLANNING','WEEKEND_GETAWAY','Weekend Getaway near Hyderabad','STARTING_FROM',3999,'per person',2);
SELECT pg_temp.item('TRAVEL_TRANSPORT','TOUR_PLANNING','PILGRIMAGE','Pilgrimage Tour Planning','QUOTE_REQUIRED',NULL,NULL,3);
SELECT pg_temp.item('TRAVEL_TRANSPORT','INTERNATIONAL','DUBAI_PKG','Dubai 4N / 5D','PACKAGE',49999,'per person',1);
SELECT pg_temp.item('TRAVEL_TRANSPORT','INTERNATIONAL','THAILAND_PKG','Thailand 5N / 6D','PACKAGE',44999,'per person',2);
SELECT pg_temp.item('TRAVEL_TRANSPORT','INTERNATIONAL','SINGAPORE_PKG','Singapore 4N / 5D','PACKAGE',59999,'per person',3);
SELECT pg_temp.item('TRAVEL_TRANSPORT','INTERNATIONAL','BALI_PKG','Bali 5N / 6D','PACKAGE',54999,'per person',4);
SELECT pg_temp.item('TRAVEL_TRANSPORT','INTERNATIONAL','EUROPE_TRIP','Europe Tour Planning','QUOTE_REQUIRED',NULL,NULL,5);
SELECT pg_temp.item('TRAVEL_TRANSPORT','VISA_TRAVEL','VISA_ASSIST','Tourist Visa Assistance','STARTING_FROM',1999,NULL,1);
SELECT pg_temp.item('TRAVEL_TRANSPORT','VISA_TRAVEL','TRAVEL_INSURANCE','Travel Insurance Assistance','FIXED',299,NULL,2);
SELECT pg_temp.item('TRAVEL_TRANSPORT','VISA_TRAVEL','FOREX','Forex Assistance','QUOTE_REQUIRED',NULL,NULL,3);

-- Every subcategory keeps an "Other ..." item for needs not on the menu (category-specific special request).
INSERT INTO service_items(subcategory_id, code, name, pricing_type, is_other, sort_order)
SELECT s.id, 'OTHER_' || s.code, 'Other ' || s.name || ' Requirement', 'QUOTE_REQUIRED', TRUE, 999
FROM subcategories s
WHERE NOT EXISTS (SELECT 1 FROM service_items i WHERE i.subcategory_id = s.id AND i.is_other)
ON CONFLICT (subcategory_id, code) DO NOTHING;

-- New subcategories are available in every active city.
INSERT INTO category_cities(category_id, city_id)
SELECT c.id, ci.id FROM categories c CROSS JOIN cities ci ON CONFLICT DO NOTHING;
