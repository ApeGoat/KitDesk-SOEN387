-- Demo data: the four categories with their limits, some models and units, and test accounts.
-- Every seeded account's password is "password" (bcrypt hash below). Demo use only.

INSERT INTO categories (code, name, max_active_per_student) VALUES
    ('CAMERA',   'Cameras',  1),
    ('LENS',     'Lenses',   2),
    ('LIGHTING', 'Lighting', 1),
    ('SOUND',    'Sound',    2);

INSERT INTO models (category_id, brand, name, description) VALUES
    ((SELECT id FROM categories WHERE code = 'CAMERA'),   'Canon',     'EOS R5',             'Full-frame mirrorless body'),
    ((SELECT id FROM categories WHERE code = 'CAMERA'),   'Sony',      'A7 IV',              'Full-frame mirrorless body'),
    ((SELECT id FROM categories WHERE code = 'LENS'),     'Canon',     'RF 24-70mm f/2.8',   'Standard zoom'),
    ((SELECT id FROM categories WHERE code = 'LENS'),     'Canon',     'RF 50mm f/1.8',      'Prime'),
    ((SELECT id FROM categories WHERE code = 'LIGHTING'), 'Aputure',   'Amaran 200d kit',    'LED light with stand and softbox'),
    ((SELECT id FROM categories WHERE code = 'SOUND'),    'Rode',      'NTG3',               'Shotgun microphone'),
    ((SELECT id FROM categories WHERE code = 'SOUND'),    'Zoom',      'H6',                 'Portable audio recorder');

INSERT INTO units (code, model_id) VALUES
    ('CAM-R5-01',   (SELECT id FROM models WHERE name = 'EOS R5')),
    ('CAM-R5-02',   (SELECT id FROM models WHERE name = 'EOS R5')),
    ('CAM-R5-03',   (SELECT id FROM models WHERE name = 'EOS R5')),
    ('CAM-A7-01',   (SELECT id FROM models WHERE name = 'A7 IV')),
    ('LEN-2470-01', (SELECT id FROM models WHERE name = 'RF 24-70mm f/2.8')),
    ('LEN-2470-02', (SELECT id FROM models WHERE name = 'RF 24-70mm f/2.8')),
    ('LEN-50-01',   (SELECT id FROM models WHERE name = 'RF 50mm f/1.8')),
    ('LEN-50-02',   (SELECT id FROM models WHERE name = 'RF 50mm f/1.8')),
    ('LEN-50-03',   (SELECT id FROM models WHERE name = 'RF 50mm f/1.8')),
    ('LGT-200D-01', (SELECT id FROM models WHERE name = 'Amaran 200d kit')),
    ('SND-NTG3-01', (SELECT id FROM models WHERE name = 'NTG3')),
    ('SND-NTG3-02', (SELECT id FROM models WHERE name = 'NTG3')),
    ('SND-H6-01',   (SELECT id FROM models WHERE name = 'H6'));

INSERT INTO users (email, password_hash, full_name, role, subscription_active) VALUES
    ('staff@kitdesk.test', '$2b$10$R.m9r6cIa02wkiKcnJSa.OHr6LHRQOdgEIfMFLzelhc8v7ZKMcC9G', 'Desk Staff',     'STAFF',   TRUE),
    ('alice@kitdesk.test', '$2b$10$R.m9r6cIa02wkiKcnJSa.OHr6LHRQOdgEIfMFLzelhc8v7ZKMcC9G', 'Alice Student',  'STUDENT', TRUE),
    ('bob@kitdesk.test',   '$2b$10$R.m9r6cIa02wkiKcnJSa.OHr6LHRQOdgEIfMFLzelhc8v7ZKMcC9G', 'Bob Student',    'STUDENT', TRUE),
    -- Carol's subscription has lapsed, for demonstrating the 403.
    ('carol@kitdesk.test', '$2b$10$R.m9r6cIa02wkiKcnJSa.OHr6LHRQOdgEIfMFLzelhc8v7ZKMcC9G', 'Carol Student',  'STUDENT', FALSE);

-- 50 subscribed students for the concurrency race script: student01..student50@kitdesk.test
INSERT INTO users (email, password_hash, full_name, role, subscription_active)
SELECT format('student%s@kitdesk.test', lpad(n::text, 2, '0')),
       '$2b$10$R.m9r6cIa02wkiKcnJSa.OHr6LHRQOdgEIfMFLzelhc8v7ZKMcC9G',
       format('Race Student %s', n),
       'STUDENT',
       TRUE
FROM generate_series(1, 50) AS n;
