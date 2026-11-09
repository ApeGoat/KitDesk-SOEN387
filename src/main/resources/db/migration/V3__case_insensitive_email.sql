-- Emails are looked up ignoring case (login accepts ALICE@kitdesk.test), so they must also be
-- unique ignoring case. Otherwise Alice@... and alice@... could both exist, and the login lookup
-- would find two accounts and fail with a 500.
CREATE UNIQUE INDEX users_email_lower_key ON users (lower(email));
