-- Demo and test accounts. Loaded ONLY with the "demo" profile (application-demo.yml), never in
-- a real deployment. Every password here is "password" (bcrypt hash below).
--
-- A repeatable migration: Flyway runs it after the versioned ones, and again only if this file
-- changes. ON CONFLICT DO NOTHING makes a re-run harmless.

INSERT INTO users (email, password_hash, full_name, role, subscription_active) VALUES
    ('staff@kitdesk.test', '$2b$10$R.m9r6cIa02wkiKcnJSa.OHr6LHRQOdgEIfMFLzelhc8v7ZKMcC9G', 'Desk Staff',     'STAFF',   TRUE),
    ('alice@kitdesk.test', '$2b$10$R.m9r6cIa02wkiKcnJSa.OHr6LHRQOdgEIfMFLzelhc8v7ZKMcC9G', 'Alice Student',  'STUDENT', TRUE),
    ('bob@kitdesk.test',   '$2b$10$R.m9r6cIa02wkiKcnJSa.OHr6LHRQOdgEIfMFLzelhc8v7ZKMcC9G', 'Bob Student',    'STUDENT', TRUE),
    -- Carol's subscription has lapsed, for demonstrating the 403.
    ('carol@kitdesk.test', '$2b$10$R.m9r6cIa02wkiKcnJSa.OHr6LHRQOdgEIfMFLzelhc8v7ZKMcC9G', 'Carol Student',  'STUDENT', FALSE)
ON CONFLICT DO NOTHING;

-- 50 subscribed students for the concurrency race script: student01..student50@kitdesk.test
INSERT INTO users (email, password_hash, full_name, role, subscription_active)
SELECT format('student%s@kitdesk.test', lpad(n::text, 2, '0')),
       '$2b$10$R.m9r6cIa02wkiKcnJSa.OHr6LHRQOdgEIfMFLzelhc8v7ZKMcC9G',
       format('Race Student %s', n),
       'STUDENT',
       TRUE
FROM generate_series(1, 50) AS n
ON CONFLICT DO NOTHING;
