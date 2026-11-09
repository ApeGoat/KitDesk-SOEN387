-- Catalogue data: the four categories with their limits, the models and the units.
-- Test accounts are not here: they are in db/demo and only load with the "demo" profile.

INSERT INTO categories (code, name, rental_limit) VALUES
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

INSERT INTO units (asset_tag, model_id) VALUES
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
