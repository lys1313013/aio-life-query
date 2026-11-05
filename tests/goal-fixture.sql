-- 隔离验收夹具：虚构用户、工资类型和运动类型，所有大整数 ID 保持字符串契约。
CREATE TABLE exercise_record (
 id BIGINT PRIMARY KEY, exercise_date DATE NOT NULL, exercise_type_id BIGINT,
 exercise_count INT NOT NULL, description TEXT, create_user BIGINT, create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
 update_time DATETIME DEFAULT CURRENT_TIMESTAMP, update_user BIGINT, time_id VARCHAR(64), user_id BIGINT NOT NULL, is_deleted TINYINT NOT NULL DEFAULT 0
);
INSERT INTO exercise_record(id,exercise_date,exercise_type_id,exercise_count,user_id,is_deleted) VALUES
 (1,'2026-10-01',9007199254740993,26,101,0), (2,'2026-10-02',9007199254740993,20,101,0),
 (3,'2026-10-01',9007199254740993,1000,102,0), (4,'2026-10-01',9007199254740993,999,101,1),
 (5,'2026-10-01',9007199254740994,1000,101,0), (6,'2027-01-01',9007199254740993,1000,101,0);
CREATE TABLE income (id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL, inc_type_id BIGINT NOT NULL,
 inc_date DATE NOT NULL, amt DECIMAL(10,2) NOT NULL, is_deleted TINYINT NOT NULL DEFAULT 0);
INSERT INTO income VALUES
 (1,101,9007199254740995,'2026-01-01',10000,0), (2,101,9007199254740995,'2026-01-31',5000,0),
 (3,101,9007199254740995,'2026-02-01',14999.99,0), (4,102,9007199254740995,'2026-01-01',999999,0),
 (5,101,9007199254740995,'2026-01-01',999999,1), (6,101,9007199254740996,'2026-01-01',999999,0),
 (7,101,9007199254740995,'2027-01-01',999999,0);
GRANT SELECT (id,exercise_date,exercise_type_id,exercise_count,description,create_user,create_time,update_time,update_user,time_id,user_id,is_deleted) ON aio_life.exercise_record TO 'fixture_read'@'%';
GRANT SELECT (user_id,inc_type_id,inc_date,amt,is_deleted) ON aio_life.income TO 'fixture_read'@'%';
