CREATE TABLE time_record (
  id BIGINT PRIMARY KEY, date DATE NOT NULL, category_id BIGINT NULL,
  duration_minutes INTEGER NOT NULL DEFAULT 420, create_user BIGINT NULL,
  start_minute INTEGER NOT NULL DEFAULT 0, end_minute INTEGER NOT NULL DEFAULT 420,
  title VARCHAR(255) NULL, description TEXT NULL, is_manual SMALLINT NOT NULL DEFAULT 1,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, update_user BIGINT NULL,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, relate_id BIGINT NULL,
  relate_type SMALLINT NULL, user_id BIGINT NOT NULL, is_deleted SMALLINT NOT NULL DEFAULT 0
);
CREATE TABLE time_tracker_category (
  id BIGINT PRIMARY KEY, user_id BIGINT NULL, template_id BIGINT NULL,
  name VARCHAR(100) NULL, is_deleted SMALLINT NOT NULL DEFAULT 0
);
INSERT INTO time_tracker_category(id,user_id,template_id,name,is_deleted) VALUES
  (1,NULL,NULL,'休息',0), (2,NULL,NULL,'工作',0), (3,NULL,NULL,'休息',1),
  (4,101,NULL,'午休',0), (5,102,NULL,'休息',0), (6,NULL,NULL,'公共原名',0),
  (7,101,6,'休息',0), (8,NULL,NULL,'休息',0), (9,101,8,'阅读',0),
  (10,102,1,'其他用户的覆盖',0), (11,101,1,'已删除的覆盖',1),
  (9007199254740993,101,NULL,'休息',0);
INSERT INTO time_record(id,date,category_id,user_id) VALUES
  (1,'2026-10-01',1,101), (2,'2026-10-02',6,101), (3,'2026-10-03',8,101),
  (4,'2026-10-04',9007199254740993,101), (5,'2026-10-05',5,101),
  (6,'2026-10-06',3,101), (7,'2026-10-07',NULL,101),
  (8,'2026-10-08',1,101), (9,'2026-10-09',1,101),
  (10,'2026-11-01',1,101), (11,'2026-10-01',6,102),
  (12,'2026-10-01',5,102);
UPDATE time_record SET start_minute=60 WHERE id=8;
UPDATE time_record SET is_deleted=1 WHERE id=9;
CREATE ROLE fixture_read LOGIN PASSWORD 'fixture-read';
GRANT USAGE ON SCHEMA public TO fixture_read;
ALTER ROLE fixture_read SET default_transaction_read_only=on;
ALTER ROLE fixture_read SET statement_timeout='5s';
GRANT SELECT (id,date,category_id,duration_minutes,create_user,start_minute,end_minute,title,description,is_manual,create_time,update_user,update_time,relate_id,relate_type,user_id,is_deleted)
  ON public.time_record TO fixture_read;
GRANT SELECT (id,user_id,template_id,name,is_deleted)
  ON public.time_tracker_category TO fixture_read;
