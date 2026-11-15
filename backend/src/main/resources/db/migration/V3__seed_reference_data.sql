-- ParkIt reference data (DATABASE_DESIGN.md section 10), values approved by the project owner 2026-11-15.
-- updated_by_user_id is NULL: "seeded by migration" (DATABASE_DESIGN.md 3.7).
-- ON CONFLICT DO NOTHING keeps the script safe if run against a database that already holds these rows;
-- existing (e.g. admin-edited) values are never overwritten.

-- Single facility row (setting_id = 1). 24/7: opening_time = closing_time is allowed only
-- because open_24_hours is TRUE (ck_hours).
INSERT INTO facility_settings (setting_id, facility_name, opening_time, closing_time, open_24_hours, timezone)
VALUES (1, 'ParkIt', TIME '00:00', TIME '00:00', TRUE, 'Asia/Kolkata')
ON CONFLICT (setting_id) DO NOTHING;

-- One current rate row per category (BR-20).
INSERT INTO parking_rates (vehicle_type, hourly_rate, daily_rate)
VALUES ('CAR',  40.00, 200.00),
       ('BIKE', 20.00, 100.00)
ON CONFLICT (vehicle_type) DO NOTHING;
