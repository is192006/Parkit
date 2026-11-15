-- ParkIt: extensions required by the core schema (DATABASE_DESIGN.md section 9).
-- btree_gist enables EXCLUDE constraints mixing "=" on BIGINT with "&&" on ranges.
CREATE EXTENSION IF NOT EXISTS btree_gist;
