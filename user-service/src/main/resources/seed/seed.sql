-- Seed data for the test profile. POST /test/seed resets first, so ids are always 1..12.
-- Pin the clock to 2026-10-15 (PUT /test/clock) so age and date rules give fixed answers.

INSERT INTO users (username, email, first_name, last_name, date_of_birth, phone, status, preferences, last_login_at, deleted_at) VALUES
 ('admin.arun',   'arun@userlab.in',       'Arun',    'Kumar',     '1985-02-11', '9840011111', 'ACTIVE',
  '{"language":"en","timezone":"Asia/Kolkata","notifications":{"email":true,"sms":true,"channels":["EMAIL","SMS"]}}', '2026-10-14 09:10:00+05:30', NULL),
 ('support.priya','priya@userlab.in',      'Priya',   'Raman',     '1990-07-23', '9840022222', 'ACTIVE',
  '{"language":"ta","timezone":"Asia/Kolkata","notifications":{"email":true,"sms":false,"channels":["EMAIL"]}}',     '2026-10-15 08:45:00+05:30', NULL),
 ('selvi.r',      'selvi@example.in',      'Selvi',   'R',         '1994-06-12', '9876543210', 'ACTIVE',
  '{"language":"ta","timezone":"Asia/Kolkata","notifications":{"email":true,"sms":false,"channels":["EMAIL","PUSH"]}}','2026-10-10 19:30:00+05:30', NULL),
 ('karthik.m',    'Karthik.M@Example.in',  'Karthik', 'Mohan',     '1988-12-01', '9840044444', 'ACTIVE',
  '{"language":"en","timezone":"Asia/Kolkata","notifications":{"email":false,"sms":true,"channels":["SMS"]}}',       '2026-09-30 12:00:00+05:30', NULL),
 ('meena.s',      'meena@example.in',      'Meena',   'Sundar',    '2001-03-19', NULL,         'PENDING_VERIFICATION',
  '{"language":"ta","timezone":"Asia/Kolkata","notifications":{"email":true,"sms":false,"channels":["EMAIL"]}}',     NULL, NULL),
 ('rahul.k',      'rahul@example.in',      'Rahul',   'Krishnan',  '1992-09-05', '9840066666', 'SUSPENDED',
  '{"language":"hi","timezone":"Asia/Kolkata","notifications":{"email":true,"sms":false,"channels":["EMAIL"]}}',     '2026-08-01 10:00:00+05:30', NULL),
 ('divya.n',      'divya@example.in',      'Divya',   'Natarajan', '1996-11-30', '9840077777', 'ACTIVE',
  '{"language":"ta","timezone":"Asia/Kolkata","notifications":{"email":true,"sms":true,"channels":["EMAIL","SMS","PUSH"]}}','2026-10-15 07:05:00+05:30', NULL),
 ('old.user',     'old.user@example.in',   'Old',     'User',      '1979-01-01', NULL,         'DELETED',
  '{"language":"en","timezone":"Asia/Kolkata","notifications":{"email":false,"sms":false,"channels":[]}}',           '2025-01-10 10:00:00+05:30', '2026-05-01 10:00:00+05:30'),
 ('anand.v',      'anand@example.in',      'Anand',   'Varma',     '1987-04-17', '9840099999', 'ACTIVE',
  '{"language":"en","timezone":"Asia/Kolkata","notifications":{"email":true,"sms":true,"channels":["SMS"]}}',        '2026-10-12 21:15:00+05:30', NULL),
 ('lakshmi.p',    'lakshmi@example.in',    'Lakshmi', 'Prasad',    '1999-08-08', '9840010101', 'ACTIVE',
  '{"language":"ta","timezone":"Asia/Kolkata","notifications":{"email":true,"sms":false,"channels":["PUSH"]}}',      '2026-10-13 18:00:00+05:30', NULL),
 ('vijay.t',      'vijay@userlab.in',      'Vijay',   'Thomas',    '1991-05-25', '9840012121', 'ACTIVE',
  '{"language":"en","timezone":"Asia/Kolkata","notifications":{"email":true,"sms":false,"channels":["EMAIL"]}}',     '2026-10-15 10:20:00+05:30', NULL),
 ('nisha.j',      'nisha@example.in',      'Nisha',   'Joseph',    '2008-10-15', NULL,         'PENDING_VERIFICATION',
  '{"language":"en","timezone":"Asia/Kolkata","notifications":{"email":true,"sms":false,"channels":["EMAIL"]}}',     NULL, NULL);

INSERT INTO user_roles (user_id, role_code) VALUES
 (1,'ADMIN'),(1,'USER'),(2,'SUPPORT'),(2,'USER'),(3,'USER'),(4,'USER'),(5,'USER'),(6,'USER'),
 (7,'USER'),(8,'USER'),(9,'USER'),(10,'USER'),(11,'SUPPORT'),(11,'USER'),(12,'USER');

INSERT INTO addresses (user_id, type, is_primary, line1, line2, city, state, pincode, lat, lon) VALUES
 (1,  'HOME',    true,  '4 Lake View Road',     'Porur',        'Chennai',    'Tamil Nadu',  '600116', 13.035400, 80.158000),
 (2,  'HOME',    true,  '18 3rd Cross',         'Indiranagar',  'Bengaluru',  'Karnataka',   '560038', 12.978400, 77.640800),
 (3,  'HOME',    true,  '12 Anna Nagar',        NULL,           'Chennai',    'Tamil Nadu',  '600040', 13.085000, 80.210000),
 (4,  'HOME',    true,  '7 Race Course Road',   NULL,           'Coimbatore', 'Tamil Nadu',  '641018', 11.001700, 76.974000),
 (6,  'HOME',    true,  '22 Jubilee Hills',     NULL,           'Hyderabad',  'Telangana',   '500033', NULL,      NULL),
 (7,  'HOME',    true,  '9 Besant Nagar',       '2nd Avenue',   'Chennai',    'Tamil Nadu',  '600090', 13.000200, 80.266800),
 (7,  'WORK',    false, 'Tidel Park',           'Taramani',     'Chennai',    'Tamil Nadu',  '600113', 12.989600, 80.248800),
 (8,  'HOME',    true,  '1 Old Street',         NULL,           'Chennai',    'Tamil Nadu',  '600001', NULL,      NULL),
 (9,  'HOME',    true,  '5 FC Road',            NULL,           'Pune',       'Maharashtra', '411004', 18.520400, 73.856700),
 (9,  'BILLING', false, 'PO Box 88',            NULL,           'Pune',       'Maharashtra', '411001', NULL,      NULL),
 (10, 'HOME',    true,  '30 Mylapore Tank St',  NULL,           'Chennai',    'Tamil Nadu',  '600004', 13.033900, 80.269400),
 (11, 'WORK',    true,  'Infopark Phase 1',     'Kakkanad',     'Kochi',      'Kerala',      '682042', 10.009600, 76.362300),
 (12, 'HOME',    true,  '14 Nehru Street',      NULL,           'Madurai',    'Tamil Nadu',  '625001', NULL,      NULL);
