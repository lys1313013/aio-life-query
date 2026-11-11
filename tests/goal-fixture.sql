-- 隔离验收夹具：虚构用户、工资类型和运动类型，所有大整数 ID 保持字符串契约。
CREATE TABLE exercise_record (
 id BIGINT PRIMARY KEY, exercise_date DATE NOT NULL, exercise_type_id BIGINT,
 exercise_count INT NOT NULL, description TEXT, create_user BIGINT, create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
 update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, update_user BIGINT, time_id VARCHAR(64), user_id BIGINT NOT NULL, is_deleted SMALLINT NOT NULL DEFAULT 0
);
INSERT INTO exercise_record(id,exercise_date,exercise_type_id,exercise_count,user_id,is_deleted) VALUES
 (1,'2026-10-01',9007199254740993,26,101,0), (2,'2026-10-02',9007199254740993,20,101,0),
 (3,'2026-10-01',9007199254740993,1000,102,0), (4,'2026-10-01',9007199254740993,999,101,1),
 (5,'2026-10-01',9007199254740994,1000,101,0), (6,'2027-01-01',9007199254740993,1000,101,0);
CREATE TABLE income (id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL, inc_type_id BIGINT NOT NULL,
 inc_date DATE NOT NULL, amt DECIMAL(10,2) NOT NULL, is_deleted SMALLINT NOT NULL DEFAULT 0);
INSERT INTO income VALUES
 (1,101,9007199254740995,'2026-01-01',10000,0), (2,101,9007199254740995,'2026-01-31',5000,0),
 (3,101,9007199254740995,'2026-02-01',14999.99,0), (4,102,9007199254740995,'2026-01-01',999999,0),
 (5,101,9007199254740995,'2026-01-01',999999,1), (6,101,9007199254740996,'2026-01-01',999999,0),
 (7,101,9007199254740995,'2027-01-01',999999,0);
GRANT SELECT (id,exercise_date,exercise_type_id,exercise_count,description,create_user,create_time,update_time,update_user,time_id,user_id,is_deleted) ON public.exercise_record TO fixture_read;
GRANT SELECT (user_id,inc_type_id,inc_date,amt,is_deleted) ON public.income TO fixture_read;

CREATE TABLE user_dict_data (
 id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL, template_id BIGINT, dict_type VARCHAR(100),
 dict_label VARCHAR(100), is_readonly CHAR(1) DEFAULT 'N', is_deleted SMALLINT DEFAULT 0,
 is_enabled SMALLINT DEFAULT 1
);
INSERT INTO user_dict_data(id,user_id,template_id,dict_type,dict_label,is_readonly,is_deleted,is_enabled) VALUES
 (9007199254740993,0,NULL,'exercise_type','俯卧撑','N',0,1),
 (9007199254740994,101,NULL,'exercise_type','深蹲','N',0,1),
 (9007199254740995,0,NULL,'income_type','工资','N',0,1),
 (9007199254740996,101,NULL,'income_type','奖金','N',0,1),
 (99001,0,NULL,'exercise_type','拉伸原名','N',0,1),
 (99002,101,99001,'exercise_type','拉伸','N',0,1),
 (99003,0,NULL,'exercise_type','只读运动','Y',0,1),
 (99004,101,99003,'exercise_type','不可生效的覆盖','N',0,1),
 (99005,101,NULL,'exercise_type','私人运动','N',0,1),
 (99006,102,NULL,'exercise_type','俯卧撑','N',0,1),
 (99007,101,NULL,'income_type','跨域类型','N',0,1),
 (99008,101,NULL,'exercise_type','俯卧撑','N',1,1),
 (99009,101,NULL,'exercise_type','停用运动','N',0,0),
 (99010,101,NULL,'exercise_type','俯卧撑','N',0,1),
 (99013,0,NULL,'income_type','基本收入','N',0,1),
 (99014,101,99013,'income_type','固定收入','N',0,1),
 (99015,0,NULL,'income_type','只读收入','Y',0,1),
 (99016,101,99015,'income_type','不可生效的覆盖','N',0,1),
 (99017,102,NULL,'income_type','工资','N',0,1),
 (99018,101,NULL,'income_type','工资','N',1,1),
 (99019,101,NULL,'income_type','工资','N',0,0);
INSERT INTO exercise_record(id,exercise_date,exercise_type_id,exercise_count,user_id) VALUES
 (11,'2026-10-03',99001,10,101),(12,'2026-10-03',99001,20,102),
 (13,'2026-10-03',99003,10,101),(14,'2026-10-03',99005,10,101),
 (15,'2026-10-03',99006,999999,101),(16,'2026-10-03',99007,999999,101),
 (17,'2026-10-03',99008,999999,101),(18,'2026-10-03',99009,10,101),
 (19,'2026-10-03',99010,5,101);
INSERT INTO income VALUES
 (8,101,99013,'2026-01-01',100,0),(9,102,99013,'2026-01-01',200,0),
 (10,101,99015,'2026-01-01',100,0),(11,101,99017,'2026-01-01',999999,0),
 (12,101,99018,'2026-01-01',999999,0),(13,101,9007199254740993,'2026-01-01',999999,0),
 (14,101,99019,'2026-03-01',5,0);
GRANT SELECT (id,user_id,template_id,dict_type,dict_label,is_readonly,is_deleted) ON public.user_dict_data TO fixture_read;
