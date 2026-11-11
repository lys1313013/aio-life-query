-- 已有 Query 只读账号增量授权（不创建角色、不变更业务表或数据）。
-- 执行条件：目标库具有当前 user_dict_data 结构，执行者为表所有者或有相应授权权限。
-- 使用 psql -v query_role=实际只读角色 -f deploy/grant-type-relations.sql；缺少变量时不执行。
-- 仅增加两项受控名称关联所需的列级 SELECT，不授予字典键值、写入或全表权限。
\set ON_ERROR_STOP on
\if :{?query_role}
GRANT SELECT (id, user_id, template_id, dict_type, dict_label, is_readonly, is_deleted)
  ON public.user_dict_data TO :"query_role";
\else
\echo '必须通过 -v query_role=实际只读角色 指定已有 Query 角色'
\quit 1
\endif
