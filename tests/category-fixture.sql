CREATE TABLE time_record (
  id VARCHAR(64) PRIMARY KEY, date DATE NOT NULL, category_id BIGINT NULL,
  duration SMALLINT UNSIGNED NOT NULL DEFAULT 420, create_user BIGINT NULL,
  start_time SMALLINT UNSIGNED NOT NULL DEFAULT 0, end_time SMALLINT UNSIGNED NOT NULL DEFAULT 420,
  title VARCHAR(255) NULL, description TEXT NULL, is_manual TINYINT NOT NULL DEFAULT 1,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, update_user BIGINT NULL,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, relate_id BIGINT NULL,
  relate_type TINYINT NULL, user_id BIGINT NOT NULL, is_deleted TINYINT NOT NULL DEFAULT 0
) DEFAULT CHARSET=utf8mb4;
CREATE TABLE time_tracker_category (
  id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL, template_id BIGINT NULL,
  name VARCHAR(100) NULL, is_deleted TINYINT NOT NULL DEFAULT 0
) DEFAULT CHARSET=utf8mb4;
INSERT INTO time_tracker_category(id,user_id,template_id,name,is_deleted) VALUES
  (1,0,NULL,'休息',0), (2,0,NULL,'工作',0), (3,0,NULL,'休息',1),
  (4,101,NULL,'午休',0), (5,102,NULL,'休息',0), (6,0,NULL,'公共原名',0),
  (7,101,6,'休息',0), (8,0,NULL,'休息',0), (9,101,8,'阅读',0),
  (10,102,1,'其他用户的覆盖',0), (11,101,1,'已删除的覆盖',1),
  (9007199254740993,101,NULL,'休息',0);
INSERT INTO time_record(id,date,category_id,user_id) VALUES
  ('a1','2026-10-01',1,101), ('a2','2026-10-02',6,101), ('a3','2026-10-03',8,101),
  ('a4','2026-10-04',9007199254740993,101), ('a5','2026-10-05',5,101),
  ('a6','2026-10-06',3,101), ('a7','2026-10-07',NULL,101),
  ('a8','2026-10-08',1,101), ('deleted-record','2026-10-09',1,101),
  ('outside-range','2026-11-01',1,101), ('other-user','2026-10-01',6,102),
  ('other-user-own','2026-10-01',5,102);
UPDATE time_record SET start_time=60 WHERE id='a8';
UPDATE time_record SET is_deleted=1 WHERE id='deleted-record';
CREATE USER 'fixture_read'@'%' IDENTIFIED BY 'fixture-read';
GRANT SELECT (id,date,category_id,duration,create_user,start_time,end_time,title,description,is_manual,create_time,update_user,update_time,relate_id,relate_type,user_id,is_deleted)
  ON aio_life.time_record TO 'fixture_read'@'%';
GRANT SELECT (id,user_id,template_id,name,is_deleted)
  ON aio_life.time_tracker_category TO 'fixture_read'@'%';
